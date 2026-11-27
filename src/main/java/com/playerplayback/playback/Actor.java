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
import net.minecraft.util.Mth;
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
public final class Actor {
    /** Ids far away from the vanilla entity id counter to avoid collisions. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1_600_000_000);

    public final String recordingName;
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

    private int frameIndex;
    private int eventIndex;
    private int lastTimeMs = Integer.MIN_VALUE;
    private Frame current;
    private Frame lastSentState;
    private double sentX = Double.NaN, sentY, sentZ;
    private float sentYaw, sentPitch, sentHead;
    private boolean dead;

    public Actor(String recordingName, Recording recording, Appearance appearance, int delayMs, Vec3 offset,
                 HolderLookup.Provider registries) {
        this.recordingName = recordingName;
        this.recording = recording;
        this.delayMs = delayMs;
        this.offset = offset;
        this.dimension = recording.dimension;
        this.registries = registries;
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
    public void restart() {
        frameIndex = 0;
        eventIndex = 0;
        lastTimeMs = Integer.MIN_VALUE;
        resetEquipment();
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
    public void update(int sceneTimeMs, List<ServerPlayer> candidates, double range, Playback.BlockSink blocks) {
        int t = sceneTimeMs - delayMs;
        if (t < 0 || recording.frames.isEmpty()) {
            despawnAll();
            return;
        }
        if (t < lastTimeMs) {
            frameIndex = 0;
            eventIndex = 0;
        }

        Frame f = sample(Math.min(t, recording.durationMs));
        Frame previous = current;
        current = f;

        // Dead actors stand back up once the recorded player respawned (big position jump).
        if (dead && previous != null && distSq(previous, f) > 16 * 16) {
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
    }

    private void sendDespawn(ServerPlayer p, boolean removeEntity) {
        if (removeEntity) send(p, PacketFactory.removeEntity(entityId));
        send(p, PacketFactory.removePlayerInfo(profile.id()));
        send(p, PacketFactory.removeTeam(team));
    }

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

    /** Interpolated state at recording time {@code t}. */
    private Frame sample(int t) {
        List<Frame> frames = recording.frames;
        while (frameIndex + 1 < frames.size() && frames.get(frameIndex + 1).timeMs() <= t) frameIndex++;
        Frame a = frames.get(frameIndex);
        if (frameIndex + 1 >= frames.size() || t <= a.timeMs()) return a;
        Frame b = frames.get(frameIndex + 1);
        int span = b.timeMs() - a.timeMs();
        if (span <= 0) return a;
        // Don't smear teleports.
        if (distSq(a, b) > 8 * 8) return a;
        float k = (t - a.timeMs()) / (float) span;
        return new Frame(t,
                Mth.lerp(k, a.x(), b.x()), Mth.lerp(k, a.y(), b.y()), Mth.lerp(k, a.z(), b.z()),
                Mth.rotLerp(k, a.yaw(), b.yaw()), Mth.lerp(k, a.pitch(), b.pitch()), Mth.rotLerp(k, a.headYaw(), b.headYaw()),
                a.onGround(), a.sharedFlags(), a.livingFlags(), a.pose());
    }

    private static double distSq(Frame a, Frame b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
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

    public Appearance appearance() {
        return appearance;
    }
}
