package com.playerplayback.recording;

import net.minecraft.nbt.CompoundTag;

/**
 * A discrete thing that happened at a point in time during a recording.
 */
public sealed interface ActorEvent permits ActorEvent.Swing, ActorEvent.Animate, ActorEvent.Hurt,
        ActorEvent.EntityEvent, ActorEvent.Equip, ActorEvent.Block,
        ActorEvent.Sound, ActorEvent.ProjectileStart, ActorEvent.ProjectileMove, ActorEvent.ProjectileEnd, ActorEvent.Attack {

    int timeMs();

    /** Arm swing. {@code animType}/{@code duration} are only meaningful on 26.1+ (swing animation component). */
    record Swing(int timeMs, boolean offHand, int animType, int duration) implements ActorEvent {}

    /** Kinds of {@link Animate}. */
    int ANIM_CRIT = 1;
    int ANIM_MAGIC_CRIT = 2;
    int ANIM_WAKE_UP = 3;

    record Animate(int timeMs, int kind) implements ActorEvent {}

    record Hurt(int timeMs, float yaw) implements ActorEvent {}

    /** A vanilla entity event byte (e.g. totem pop, death). */
    record EntityEvent(int timeMs, byte id) implements ActorEvent {}

    /** Equipment slot change. {@code item} is the encoded item stack (empty tag = empty stack). */
    record Equip(int timeMs, String slot, CompoundTag item) implements ActorEvent {}

    /** Block placed/broken by the recorded player. {@code state} is the new block state. */
    record Block(int timeMs, int x, int y, int z, CompoundTag state, boolean broken) implements ActorEvent {}

    /** A sound made by the recorded player, at an absolute position. */
    record Sound(int timeMs, String sound, String source, double x, double y, double z, float volume, float pitch, long seed) implements ActorEvent {}

    /**
     * A projectile thrown/shot by the recorded player (ender pearl, arrow, trident, potion...).
     * {@code id} is local to the recording; {@code item} is the displayed item (may be empty).
     */
    record ProjectileStart(int timeMs, int id, String type, double x, double y, double z,
                           double vx, double vy, double vz, float yaw, float pitch, CompoundTag item) implements ActorEvent {}

    record ProjectileMove(int timeMs, int id, double x, double y, double z, float yaw, float pitch) implements ActorEvent {}

    record ProjectileEnd(int timeMs, int id) implements ActorEvent {}

    /**
     * The player attacked something standing at (x, y, z). In real mode the actor attacks whatever
     * is there on replay, with the same attack strength and critical-hit state.
     */
    record Attack(int timeMs, double x, double y, double z, float strength, boolean crit) implements ActorEvent {}
}
