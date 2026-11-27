package com.playerplayback.recording;

import net.minecraft.nbt.CompoundTag;

/**
 * A discrete thing that happened at a point in time during a recording.
 */
public sealed interface ActorEvent permits ActorEvent.Swing, ActorEvent.Animate, ActorEvent.Hurt,
        ActorEvent.EntityEvent, ActorEvent.Equip, ActorEvent.Block {

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
}
