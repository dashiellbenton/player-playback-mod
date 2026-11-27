package com.playerplayback.playback;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import com.playerplayback.actor.Appearance;
import com.playerplayback.recording.ActorEvent;
import com.playerplayback.recording.Frame;
import com.playerplayback.recording.Recording;
import com.playerplayback.util.Compat;
import com.playerplayback.util.ItemCodec;
import com.playerplayback.util.Names;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fake player shown to nearby players purely through packets. It never exists as a server
 * entity, so hundreds of actors cost almost nothing.
 * <p>
 * Accessed only while holding {@link PlaybackManager#LOCK}.
 */
public final class Actor implements PlaybackActor {
    /** Ids far away from the vanilla entity id counter to avoid collisions. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_600_000_000);

    public final String recordingName;
    /** Scene entry id, or null when playing a recording on its own. */
    public final String entryId;
    public final double speed;
    public final Recording recording;
    public final int delayMs;
    public final Vec3 offset;
    public final String dimension;
    private final HolderLookup.Provider registries;

    private final int entityId = NEXT_ID.getAndIncrement();
    private GameProfile profile;
    private PlayerTeam team;
    private Appearance appearance;

    private final Map<ServerPlayer, ServerLevel> viewers = new IdentityHashMap<>();
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);

    private final FrameCursor cursor;
    private int eventIndex;
    private int lastTimeMs = Integer.MIN_VALUE;
    private Frame current;
    private Frame lastSentState;
    private double sentX = Double.NaN, sentY, sentZ;
    private float sentYaw, sentPitch, sentHead;
    private boolean dead;

    /** A projectile currently in flight on the playback timeline. */
    private static final class Proj {
        final int entityId = NEXT_ID.getAndIncrement();
        net.minecraft.world.entity.EntityType<?> type;
        double x, y, z;
        float yaw, pitch;
        Vec3 velocity;
        ItemStack item;
    }

    private final Map<Integer, Proj> projectiles = new HashMap<>();

    public Actor(String recordingName, String entryId, Recording recording, Appearance appearance, int delayMs, double speed,
                 Vec3 offset, HolderLookup.Provider registries) {
        this.recordingName = recordingName;
        this.entryId = entryId;
        this.speed = speed <= 0 ? 1.0 : speed;
        this.recording = recording;
        this.delayMs = delayMs;
        this.offset = offset;
        this.dimension = recording.dimension;
        this.registries = registries;
        this.cursor = new FrameCursor(recording);
        setAppearance(appearance);
        resetEquipment();
    }

    public int entityId() {
        return entityId;
    }

    public String displayName() {
        return profile.name();
    }

    /** Changes name/skin/nametag. Viewers get the actor respawned with the new look. */
    @Override
    public void setAppearance(Appearance a) {
        boolean respawn = profile != null;
        if (respawn) despawnAll();
        this.appearance = a;
        String name = Names.profileName(a.name != null ? a.name : recording.originalName);
        String skinValue = a.skinValue != null ? a.skinValue : recording.skinValue;
        String skinSig = a.skinValue != null ? a.skinSignature : recording.skinSignature;
        PropertyMap props = PropertyMap.EMPTY;
        if (skinValue != null) {
            props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", skinValue, skinSig)));
        }
        UUID uuid = UUID.randomUUID();
        // Version-2 UUIDs never belong to real accounts, so they cannot collide with an online player.
        uuid = new UUID((uuid.getMostSignificantBits() & ~0xF000L) | 0x2000L, uuid.getLeastSignificantBits());
        this.profile = new GameProfile(uuid, name, props);
        this.team = PacketFactory.team("pp" + Integer.toHexString(entityId), name, Boolean.TRUE.equals(a.hideNametag), null);
    }

    private void resetEquipment() {
        equipment.clear();
        for (var entry : recording.initialEquipment.entrySet()) {
            EquipmentSlot slot = slotByName(entry.getKey());
            if (slot != null) equipment.put(slot, ItemCodec.decode(entry.getValue(), registries));
        }
    }

    /** Called when a looping playback restarts. */
    @Override
    public void restart() {
        cursor.reset();
        eventIndex = 0;
        lastTimeMs = Integer.MIN_VALUE;
        resetEquipment();
        removeAllProjectiles();
        if (dead) {
            dead = false;
            despawnAll();
        } else {
            broadcast(PacketFactory.equipment(entityId, equipment));
        }
    }

    /**
     * Advances the actor to {@code sceneTimeMs} on the playback timeline.
     *
     * @param candidates players that could see the actor (server snapshot)
     * @param range      tracking range in blocks
     */
    @Override
    public void update(int sceneTimeMs, List<ServerPlayer> candidates, double range, Playback.BlockSink blocks) {
        int t = (int) ((sceneTimeMs - delayMs) * speed);
        if (t < 0 || recording.frames.isEmpty()) {
            despawnAll();
            return;
        }
        if (t < lastTimeMs) {
            cursor.reset();
            eventIndex = 0;
        }

        Frame f = cursor.sample(Math.min(t, recording.durationMs));
        Frame previous = current;
        current = f;

        // Dead actors stand back up once the recorded player respawned (big position jump).
        if (dead && previous != null && FrameCursor.distSq(previous, f) > 16 * 16) {
            dead = false;
            despawnAll();
        }

        updateViewers(candidates, range);

        // Events between the last update and now.
        List<ActorEvent> events = recording.events;
        while (eventIndex < events.size() && events.get(eventIndex).timeMs() <= t) {
            fire(events.get(eventIndex), blocks);
            eventIndex++;
        }

        sendMovement(f);
        lastTimeMs = t;
    }

    private void fire(ActorEvent event, Playback.BlockSink blocks) {
        switch (event) {
            case ActorEvent.Swing s -> broadcast(Compat.swingPacket(entityId, s));
            case ActorEvent.Animate a -> broadcast(Compat.animatePacket(entityId, a.kind()));
            case ActorEvent.Hurt h -> broadcast(PacketFactory.hurt(entityId, h.yaw()));
            case ActorEvent.EntityEvent e -> {
                broadcast(PacketFactory.entityEvent(entityId, e.id()));
                if (e.id() == Compat.EVENT_DEATH) dead = true;
            }
            case ActorEvent.Equip eq -> {
                EquipmentSlot slot = slotByName(eq.slot());
                if (slot != null) {
                    ItemStack stack = ItemCodec.decode(eq.item(), registries);
                    equipment.put(slot, stack);
                    broadcast(PacketFactory.equipment(entityId, Map.of(slot, stack)));
                }
            }
            case ActorEvent.Block b -> {
                if (blocks != null) blocks.apply(dimension, b.x() + offset.x, b.y() + offset.y, b.z() + offset.z, b.state(), b.broken());
            }
            case ActorEvent.Sound so -> broadcast(PacketFactory.sound(so.sound(), so.source(),
                    so.x() + offset.x, so.y() + offset.y, so.z() + offset.z, so.volume(), so.pitch(), so.seed()));
            case ActorEvent.ProjectileStart ps -> {
                var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(ps.type()));
                if (type == null) return;
                Proj p = new Proj();
                p.type = type;
                p.x = ps.x() + offset.x;
                p.y = ps.y() + offset.y;
                p.z = ps.z() + offset.z;
                p.yaw = ps.yaw();
                p.pitch = ps.pitch();
                p.velocity = new Vec3(ps.vx(), ps.vy(), ps.vz());
                p.item = ItemCodec.decode(ps.item(), registries);
                Proj old = projectiles.put(ps.id(), p);
                if (old != null) broadcast(PacketFactory.removeEntity(old.entityId));
                for (ServerPlayer viewer : viewers.keySet()) spawnProjectile(viewer, p);
            }
            case ActorEvent.ProjectileMove pm -> {
                Proj p = projectiles.get(pm.id());
                if (p == null) return;
                p.x = pm.x() + offset.x;
                p.y = pm.y() + offset.y;
                p.z = pm.z() + offset.z;
                p.yaw = pm.yaw();
                p.pitch = pm.pitch();
                broadcast(PacketFactory.teleport(p.entityId, p.x, p.y, p.z, p.yaw, p.pitch, false));
            }
            case ActorEvent.ProjectileEnd pe -> {
                Proj p = projectiles.remove(pe.id());
                if (p != null) broadcast(PacketFactory.removeEntity(p.entityId));
            }
            case ActorEvent.Attack at -> { /* visual actors can't hit anything */ }
        }
    }

    private void updateViewers(List<ServerPlayer> candidates, double range) {
        // Remove first, then add (a respawned player is a new object on the same connection).
        Iterator<Map.Entry<ServerPlayer, ServerLevel>> it = viewers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ServerPlayer, ServerLevel> e = it.next();
            ServerPlayer p = e.getKey();
            if (p.hasDisconnected() || !candidates.contains(p)) {
                sendDespawn(p, true);
                it.remove();
            } else if (p.level() != e.getValue()) {
                // Changed dimension: the client already dropped the entity.
                sendDespawn(p, false);
                it.remove();
            } else if (!inRange(p, range + 8)) {
                sendDespawn(p, true);
                it.remove();
            }
        }
        for (ServerPlayer p : candidates) {
            if (viewers.containsKey(p) || p.hasDisconnected()) continue;
            if (!Compat.dimensionId(p).equals(dimension) || !inRange(p, range)) continue;
            sendSpawn(p);
            viewers.put(p, p.level());
        }
    }

    private boolean inRange(ServerPlayer p, double range) {
        if (current == null) return false;
        double dx = p.getX() - (current.x() + offset.x);
        double dz = p.getZ() - (current.z() + offset.z);
        return dx * dx + dz * dz <= range * range;
    }

    private void sendSpawn(ServerPlayer p) {
        Frame f = current;
        send(p, PacketFactory.addPlayerInfo(profile));
        send(p, PacketFactory.addTeam(team));
        send(p, PacketFactory.addEntity(entityId, profile.id(), f, offset));
        send(p, PacketFactory.fullData(entityId, f));
        send(p, PacketFactory.rotateHead(entityId, f.headYaw()));
        if (!equipment.isEmpty()) send(p, PacketFactory.equipment(entityId, equipment));
        for (Proj proj : projectiles.values()) spawnProjectile(p, proj);
    }

    private void spawnProjectile(ServerPlayer viewer, Proj p) {
        send(viewer, PacketFactory.addProjectile(p.entityId, p.type, p.x, p.y, p.z, p.yaw, p.pitch, p.velocity, entityId));
        if (!p.item.isEmpty()) send(viewer, PacketFactory.projectileItem(p.entityId, p.item));
    }

    private void removeAllProjectiles() {
        for (Proj p : projectiles.values()) broadcast(PacketFactory.removeEntity(p.entityId));
        projectiles.clear();
    }

    private void sendDespawn(ServerPlayer p, boolean removeEntity) {
        if (removeEntity) {
            send(p, PacketFactory.removeEntity(entityId));
            for (Proj proj : projectiles.values()) send(p, PacketFactory.removeEntity(proj.entityId));
        }
        send(p, PacketFactory.removePlayerInfo(profile.id()));
        send(p, PacketFactory.removeTeam(team));
    }

    /** Removes the actor and its projectiles from every viewer. */
    @Override
    public void despawnAll() {
        for (ServerPlayer p : viewers.keySet()) sendDespawn(p, true);
        viewers.clear();
        sentX = Double.NaN;
        lastSentState = null;
    }

    private void sendMovement(Frame f) {
        if (viewers.isEmpty()) return;
        double x = f.x() + offset.x, y = f.y() + offset.y, z = f.z() + offset.z;
        if (Double.isNaN(sentX) || x != sentX || y != sentY || z != sentZ || f.yaw() != sentYaw || f.pitch() != sentPitch) {
            broadcast(PacketFactory.teleport(entityId, x, y, z, f.yaw(), f.pitch(), f.onGround()));
            sentX = x;
            sentY = y;
            sentZ = z;
            sentYaw = f.yaw();
            sentPitch = f.pitch();
        }
        if (f.headYaw() != sentHead) {
            broadcast(PacketFactory.rotateHead(entityId, f.headYaw()));
            sentHead = f.headYaw();
        }
        if (lastSentState == null || lastSentState.sharedFlags() != f.sharedFlags()
                || lastSentState.livingFlags() != f.livingFlags() || lastSentState.pose() != f.pose()) {
            broadcast(PacketFactory.stateDataPacket(entityId, f));
            lastSentState = f;
        }
    }

    private void broadcast(Packet<?> packet) {
        for (ServerPlayer p : viewers.keySet()) send(p, packet);
    }

    private static void send(ServerPlayer p, Packet<?> packet) {
        if (p.connection != null) p.connection.send(packet);
    }

    static EquipmentSlot slotByName(String name) {
        for (EquipmentSlot s : EquipmentSlot.values()) if (s.getName().equals(name)) return s;
        return null;
    }

    /** Length of this actor on the scene timeline. */
    @Override
    public int endMs() {
        return delayMs + (int) Math.ceil(recording.durationMs / speed);
    }

    @Override
    public String recordingName() {
        return recordingName;
    }

    @Override
    public String entryId() {
        return entryId;
    }

    @Override
    public boolean real() {
        return false;
    }

    public Appearance appearance() {
        return appearance;
    }
}
