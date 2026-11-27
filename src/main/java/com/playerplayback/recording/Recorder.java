package com.playerplayback.recording;

import com.mojang.authlib.properties.Property;
import com.playerplayback.util.Compat;
import com.playerplayback.util.ItemCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Captures one player's state into a {@link Recording}.
 * <p>
 * All timestamps use {@link System#nanoTime()} (real time), never the server tick counter, so a
 * recording made while {@code /tick rate} is changed (or frozen/sprinting) still plays back at the
 * speed the player actually moved. Samples are taken every server tick <em>and</em> every time a
 * movement packet from the player is handled, so motion keeps full detail even at very low tick rates.
 */
public final class Recorder {
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    /** Never store two samples closer than this, unless the state changed. */
    private static final long MIN_SAMPLE_NANOS = 15_000_000L;
    /** Safety cap: 2 hours. */
    private static final int MAX_DURATION_MS = 2 * 60 * 60 * 1000;

    public final String name;
    public final UUID playerId;
    public final boolean recordBlocks;
    /** Scene being played alongside this recording (layered takes), or null. */
    public final String scene;
    public int scenePlaybackId = -1;

    private final Recording recording = new Recording();
    private final long startNanos;
    private long lastSampleNanos = Long.MIN_VALUE;
    private Frame lastFrame;
    private final Map<EquipmentSlot, ItemStack> lastEquipment = new EnumMap<>(EquipmentSlot.class);
    private boolean full;

    public Recorder(String name, ServerPlayer player, boolean recordBlocks, String scene) {
        this.name = name;
        this.playerId = player.getUUID();
        this.recordBlocks = recordBlocks;
        this.scene = scene;
        this.startNanos = System.nanoTime();

        recording.originalName = Compat.profileName(player.getGameProfile());
        recording.originalUuid = player.getUUID();
        recording.createdAt = System.currentTimeMillis();
        recording.dimension = Compat.dimensionId(player);
        Property textures = Compat.texturesOf(player.getGameProfile());
        if (textures != null) {
            recording.skinValue = textures.value();
            recording.skinSignature = textures.signature();
        }
        HolderLookup.Provider regs = player.registryAccess();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot).copy();
            lastEquipment.put(slot, stack);
            recording.initialEquipment.put(slot.getName(), ItemCodec.encode(stack, regs));
        }
        sample(player, true);
    }

    public int nowMs() {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    public boolean isFull() {
        return full;
    }

    /** Record the player's current state. Called from the server thread. */
    public void sample(ServerPlayer player, boolean force) {
        if (full) return;
        long now = System.nanoTime();
        int t = (int) ((now - startNanos) / 1_000_000L);
        if (t > MAX_DURATION_MS) {
            full = true;
            return;
        }
        Frame frame = capture(player, t);
        if (!force && now - lastSampleNanos < MIN_SAMPLE_NANOS && frame.sameStateAs(lastFrame)) return;
        if (!force && frame.sameStateAs(lastFrame) && now - lastSampleNanos < 1_000_000_000L) {
            // Nothing changed; still keep a sparse heartbeat so interpolation stays accurate.
            checkEquipment(player, t);
            return;
        }
        recording.frames.add(frame);
        lastFrame = frame;
        lastSampleNanos = now;
        checkEquipment(player, t);
    }

    private void checkEquipment(ServerPlayer player, int t) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack current = player.getItemBySlot(slot);
            ItemStack previous = lastEquipment.get(slot);
            if (!ItemStack.matches(current, previous)) {
                ItemStack copy = current.copy();
                lastEquipment.put(slot, copy);
                recording.events.add(new ActorEvent.Equip(t, slot.getName(), ItemCodec.encode(copy, player.registryAccess())));
            }
        }
    }

    public void event(ActorEvent event) {
        if (!full) recording.events.add(event);
    }

    public void blockChange(int x, int y, int z, CompoundTag state, boolean broken) {
        if (recordBlocks) event(new ActorEvent.Block(nowMs(), x, y, z, state, broken));
    }

    public Recording finish(ServerPlayer player) {
        if (player != null) sample(player, true);
        recording.durationMs = lastFrame == null ? 0 : Math.max(lastFrame.timeMs(), nowMs());
        // Make sure the final pose is held until the end.
        if (lastFrame != null && lastFrame.timeMs() < recording.durationMs) {
            Frame f = lastFrame;
            recording.frames.add(new Frame(recording.durationMs, f.x(), f.y(), f.z(), f.yaw(), f.pitch(), f.headYaw(),
                    f.onGround(), f.sharedFlags(), f.livingFlags(), f.pose()));
        }
        recording.events.sort(java.util.Comparator.comparingInt(ActorEvent::timeMs));
        return recording;
    }

    public int frameCount() {
        return recording.frames.size();
    }

    static Frame capture(ServerPlayer p, int t) {
        int flags = 0;
        if (p.isOnFire()) flags |= 1;
        if (p.isShiftKeyDown()) flags |= 1 << 1;
        if (p.isSprinting()) flags |= 1 << 3;
        if (p.isSwimming()) flags |= 1 << 4;
        if (p.isInvisible()) flags |= 1 << 5;
        if (p.hasGlowingTag()) flags |= 1 << 6;
        if (p.isFallFlying()) flags |= 1 << 7;

        int living = 0;
        if (p.isUsingItem()) {
            living |= 1;
            if (p.getUsedItemHand() == InteractionHand.OFF_HAND) living |= 2;
        }
        if (p.isAutoSpinAttack()) living |= 4;

        Pose pose = p.getPose();
        if (pose == Pose.SLEEPING || pose == Pose.DYING) pose = Pose.STANDING;

        return new Frame(t, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.getYHeadRot(),
                p.onGround(), (byte) flags, (byte) living, (byte) pose.ordinal());
    }
}
