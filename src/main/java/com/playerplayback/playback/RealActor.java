package com.playerplayback.playback;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.playerplayback.actor.Appearance;
import com.playerplayback.mixin.AvatarAccessor;
import com.playerplayback.mixin.EntityAccessor;
import com.playerplayback.mixin.LivingEntityAccessor;
import com.playerplayback.mixin.LivingEntityAttackAccessor;
import com.playerplayback.mixin.PlayerListAccessor;
import com.playerplayback.recording.ActorEvent;
import com.playerplayback.recording.Frame;
import com.playerplayback.recording.Recording;
import com.playerplayback.util.ItemCodec;
import com.playerplayback.util.Names;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.util.*;

/**
 * An actor that is a real fake player on the server: mobs target it, it can be hit and killed,
 * it loads chunks, its projectiles are real (pearls teleport it, arrows deal damage) and its
 * recorded melee hits are repeated for real. It still follows its recorded path; knockback pushes
 * it off the path briefly and it then eases back on.
 * <p>
 * Only touched on the server thread.
 */
public final class RealActor implements PlaybackActor {
    private static final Pose[] POSES = Pose.values();
    /** Recorded sounds that the fake player will produce by itself when the action happens for real. */
    private static final String[] REGENERATED_SOUNDS = {
            "minecraft:entity.player.attack", "minecraft:entity.player.hurt", "minecraft:entity.player.death",
            "minecraft:entity.ender_pearl", "minecraft:entity.player.teleport", "minecraft:entity.arrow.hit"
    };

    /** Every real-mode fake player currently in a world (server thread only). */
    public static final Set<ServerPlayer> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private final String recordingName;
    private final String entryId;
    private final Recording recording;
    private final int delayMs;
    private final double speed;
    private final Vec3 offset;
    private final MinecraftServer server;
    private final FrameCursor cursor;
    private Appearance appearance;

    private ServerPlayer fake;
    private GameProfile profile;
    private PlayerTeam team;
    private int eventIndex;
    private int lastTimeMs = Integer.MIN_VALUE;
    /** Died for real (killed by someone, or by the script); stays gone until the playback loops. */
    private boolean gone;
    private int deadTicks;
    private Vec3 knockOffset = Vec3.ZERO;
    private Vec3 knockVelocity = Vec3.ZERO;
    private final Map<Integer, Entity> projectiles = new HashMap<>();

    public RealActor(String recordingName, String entryId, Recording recording, Appearance appearance, int delayMs,
                     double speed, Vec3 offset, MinecraftServer server) {
        this.recordingName = recordingName;
        this.entryId = entryId;
        this.recording = recording;
        this.appearance = appearance;
        this.delayMs = delayMs;
        this.speed = speed <= 0 ? 1.0 : speed;
        this.offset = offset;
        this.server = server;
        this.cursor = new FrameCursor(recording);
    }

    @Override public String recordingName() { return recordingName; }
    @Override public String entryId() { return entryId; }
    @Override public boolean real() { return true; }

    @Override
    public int endMs() {
        return delayMs + (int) Math.ceil(recording.durationMs / speed);
    }

    public ServerPlayer player() {
        return fake;
    }

    @Override
    public void setAppearance(Appearance a) {
        this.appearance = a;
        if (fake != null) removeFake(); // respawns with the new look on the next update
    }

    @Override
    public void restart() {
        removeFake();
        cursor.reset();
        eventIndex = 0;
        lastTimeMs = Integer.MIN_VALUE;
        gone = false;
    }

    @Override
    public void despawnAll() {
        removeFake();
    }

    @Override
    public void update(int sceneTimeMs, List<ServerPlayer> candidates, double range, Playback.BlockSink blocks) {
        int t = (int) ((sceneTimeMs - delayMs) * speed);
        if (t < 0 || recording.frames.isEmpty() || gone) {
            if (!gone) removeFake();
            return;
        }
        if (t < lastTimeMs) {
            cursor.reset();
            eventIndex = 0;
        }
        Frame f = cursor.sample(Math.min(t, recording.durationMs));

        if (fake == null) {
            if (!spawn(f)) {
                gone = true;
                return;
            }
        }
        if (fake.isRemoved() && !fake.isDeadOrDying()) { // removed by someone else (e.g. /kill handled oddly)
            gone = true;
            removeFake();
            return;
        }
        if (fake.isDeadOrDying()) {
            // Real death: play the death animation, then leave the scene.
            fake.doTick();
            if (++deadTicks >= 20) {
                gone = true;
                removeFake();
            }
            return;
        }

        // Knockback: whatever velocity was applied since last update pushes us off the path...
        Vec3 dm = fake.getDeltaMovement();
        if (dm.lengthSqr() > 1.0E-6) knockVelocity = knockVelocity.add(dm);
        fake.setDeltaMovement(Vec3.ZERO);
        fake.doTick();

        knockOffset = knockOffset.add(knockVelocity);
        knockVelocity = new Vec3(knockVelocity.x * 0.6, knockVelocity.y - 0.08, knockVelocity.z * 0.6);
        if (knockOffset.y <= 0) {
            knockOffset = new Vec3(knockOffset.x, 0, knockOffset.z);
            knockVelocity = new Vec3(knockVelocity.x, 0, knockVelocity.z);
        }
        // ...and we ease back onto the recorded path.
        if (knockVelocity.horizontalDistanceSqr() < 0.0025) {
            knockOffset = new Vec3(knockOffset.x * 0.85, knockOffset.y, knockOffset.z * 0.85);
            if (knockOffset.lengthSqr() < 1.0E-4) knockOffset = Vec3.ZERO;
        }

        applyFrame(f);

        List<ActorEvent> events = recording.events;
        while (eventIndex < events.size() && events.get(eventIndex).timeMs() <= t) {
            fire(events.get(eventIndex), blocks);
            eventIndex++;
            if (fake == null) return; // died from an event
        }
        lastTimeMs = t;
    }

    private void applyFrame(Frame f) {
        double x = f.x() + offset.x + knockOffset.x, y = f.y() + offset.y + knockOffset.y, z = f.z() + offset.z + knockOffset.z;
        fake.snapTo(x, y, z, f.yaw(), f.pitch());
        fake.setYHeadRot(f.headYaw());
        fake.setOnGround(f.onGround() && knockOffset.y == 0);
        int poseIndex = f.pose() & 0xFF;
        fake.setPose(poseIndex < POSES.length ? POSES[poseIndex] : Pose.STANDING);
        byte flags = f.sharedFlags();
        if (fake.getRemainingFireTicks() > 0) flags |= 1;
        else flags &= ~1;
        fake.getEntityData().set(EntityAccessor.pp$sharedFlags(), flags);
        fake.getEntityData().set(LivingEntityAccessor.pp$livingFlags(), f.livingFlags());
        fake.level().getChunkSource().move(fake);
    }

    private boolean spawn(Frame f) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(recording.dimension)));
        if (level == null) return false;
        String name = Names.profileName(appearance.name != null ? appearance.name : recording.originalName);
        String skinValue = appearance.skinValue != null ? appearance.skinValue : recording.skinValue;
        String skinSig = appearance.skinValue != null ? appearance.skinSignature : recording.skinSignature;
        PropertyMap props = skinValue == null ? PropertyMap.EMPTY
                : new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", skinValue, skinSig)));
        UUID uuid = UUID.randomUUID();
        uuid = new UUID((uuid.getMostSignificantBits() & ~0xF000L) | 0x2000L, uuid.getLeastSignificantBits());
        profile = new GameProfile(uuid, name, props);

        fake = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(server, new FakeConnection(), fake,
                CommonListenerCookie.createInitial(profile, false));
        ((com.playerplayback.mixin.ServerGamePacketListenerAccessor) listener).pp$markClientLoaded();
        fake.setGameMode(GameType.SURVIVAL);
        fake.getEntityData().set(AvatarAccessor.pp$customisation(), (byte) 0x7F);
        knockOffset = Vec3.ZERO;
        knockVelocity = Vec3.ZERO;
        deadTicks = 0;
        fake.snapTo(f.x() + offset.x, f.y() + offset.y, f.z() + offset.z, f.yaw(), f.pitch());
        fake.setYHeadRot(f.headYaw());

        // Equipment as of the current point in the take.
        for (var e : recording.initialEquipment.entrySet()) {
            EquipmentSlot slot = Actor.slotByName(e.getKey());
            if (slot != null) fake.setItemSlot(slot, ItemCodec.decode(e.getValue(), server.registryAccess()));
        }
        for (int i = 0; i < eventIndex; i++) {
            if (recording.events.get(i) instanceof ActorEvent.Equip eq) {
                EquipmentSlot slot = Actor.slotByName(eq.slot());
                if (slot != null) fake.setItemSlot(slot, ItemCodec.decode(eq.item(), server.registryAccess()));
            }
        }

        team = PacketFactory.team("pr" + Integer.toHexString(fake.getId()), name, Boolean.TRUE.equals(appearance.hideNametag), null);
        // Clients can only show player entities they have a player-info entry for (kept out of the tab list).
        for (ServerPlayer p : server.getPlayerList().getPlayers()) introduceTo(p);
        level.addNewPlayer(fake);
        LIVE.add(fake);
        return true;
    }

    /** Sends the player-info entry and nametag team to a (newly joined) real player. */
    public void introduceTo(ServerPlayer viewer) {
        if (fake == null || viewer.connection == null) return;
        viewer.connection.send(PacketFactory.addPlayerInfo(profile));
        viewer.connection.send(PacketFactory.addTeam(team));
    }

    private void removeFake() {
        for (Entity p : projectiles.values()) if (!p.isRemoved()) p.discard();
        projectiles.clear();
        if (fake == null) return;
        ServerPlayer f = fake;
        fake = null;
        LIVE.remove(f);
        if (!f.isRemoved()) f.level().removePlayerImmediately(f, Entity.RemovalReason.DISCARDED);
        Packet<?> info = PacketFactory.removePlayerInfo(profile.id());
        Packet<?> teamRemove = PacketFactory.removeTeam(team);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(info);
            p.connection.send(teamRemove);
        }
        // Fake players register stats/advancements like real ones; don't leak them.
        PlayerListAccessor list = (PlayerListAccessor) server.getPlayerList();
        list.pp$stats().remove(f.getUUID());
        var adv = list.pp$advancements().remove(f.getUUID());
        if (adv != null) com.playerplayback.util.Compat.stopAdvancements(adv);
    }

    private void fire(ActorEvent event, Playback.BlockSink blocks) {
        ServerLevel level = (ServerLevel) fake.level();
        switch (event) {
            case ActorEvent.Swing s -> com.playerplayback.util.Compat.swingEntity(fake, s.offHand());
            case ActorEvent.Animate a -> sendToTracking(level, com.playerplayback.util.Compat.animatePacket(fake.getId(), a.kind()));
            case ActorEvent.Hurt h -> { /* real damage makes its own hurt effects */ }
            case ActorEvent.EntityEvent e -> {
                if (e.id() == com.playerplayback.util.Compat.EVENT_DEATH) {
                    if (!fake.isDeadOrDying()) fake.kill(level);
                } else {
                    level.broadcastEntityEvent(fake, e.id());
                }
            }
            case ActorEvent.Equip eq -> {
                EquipmentSlot slot = Actor.slotByName(eq.slot());
                if (slot != null) fake.setItemSlot(slot, ItemCodec.decode(eq.item(), server.registryAccess()));
            }
            case ActorEvent.Block b -> {
                if (blocks != null) blocks.apply(recording.dimension, b.x() + offset.x, b.y() + offset.y, b.z() + offset.z, b.state(), b.broken());
            }
            case ActorEvent.Sound so -> {
                for (String prefix : REGENERATED_SOUNDS) if (so.sound().startsWith(prefix)) return;
                SoundSource source = SoundSource.PLAYERS;
                for (SoundSource src : SoundSource.values()) if (src.getName().equals(so.source())) source = src;
                Holder<SoundEvent> holder = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(so.sound())));
                level.playSeededSound(null, so.x() + offset.x, so.y() + offset.y, so.z() + offset.z, holder, source, so.volume(), so.pitch(), so.seed());
            }
            case ActorEvent.ProjectileStart ps -> spawnProjectile(level, ps);
            case ActorEvent.ProjectileMove pm -> { /* real physics */ }
            case ActorEvent.ProjectileEnd pe -> projectiles.remove(pe.id());
            case ActorEvent.Attack at -> attack(level, at);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sendToTracking(ServerLevel level, Packet<?> packet) {
        level.getChunkSource().sendToTrackingPlayers(fake, (Packet) packet);
    }

    private void spawnProjectile(ServerLevel level, ActorEvent.ProjectileStart ps) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(ps.type()));
        if (type == null) return;
        Entity e = type.create(level, EntitySpawnReason.TRIGGERED);
        if (e == null) return;
        // Same position, velocity and rotation as in the take, so the flight is identical every time.
        e.snapTo(ps.x() + offset.x, ps.y() + offset.y, ps.z() + offset.z, ps.yaw(), ps.pitch());
        e.setDeltaMovement(ps.vx(), ps.vy(), ps.vz());
        if (e instanceof Projectile p) p.setOwner(fake);
        if (e instanceof ThrowableItemProjectile thrown) {
            ItemStack item = ItemCodec.decode(ps.item(), server.registryAccess());
            if (!item.isEmpty()) thrown.setItem(item);
        }
        level.addFreshEntity(e);
        projectiles.put(ps.id(), e);
    }

    private void attack(ServerLevel level, ActorEvent.Attack at) {
        Vec3 where = new Vec3(at.x() + offset.x + knockOffset.x, at.y() + offset.y, at.z() + offset.z + knockOffset.z);
        AABB box = new AABB(where, where).inflate(1.5);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : level.getEntities(fake, box, e -> e instanceof LivingEntity le && le.isAlive() && !e.isSpectator())) {
            double d = e.position().distanceToSqr(where);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best == null) return;
        // Same attack strength and critical-hit state as in the recording.
        int delay = Math.max(1, (int) fake.getCurrentItemAttackStrengthDelay());
        ((LivingEntityAttackAccessor) fake).pp$setAttackStrengthTicker((int) Math.ceil(at.strength() * delay));
        boolean wasOnGround = fake.onGround();
        if (at.crit()) {
            fake.fallDistance = 1.0;
            fake.setOnGround(false);
            fake.setSprinting(false);
        }
        fake.attack(best);
        fake.fallDistance = 0;
        fake.setOnGround(wasOnGround);
    }
}
