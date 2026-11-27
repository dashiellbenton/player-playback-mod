package com.playerplayback.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> pp$sharedFlags() {
        throw new AssertionError();
    }

    @Accessor("DATA_POSE")
    static EntityDataAccessor<Pose> pp$pose() {
        throw new AssertionError();
    }
}
