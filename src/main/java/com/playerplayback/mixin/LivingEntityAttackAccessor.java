package com.playerplayback.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAttackAccessor {
    @Accessor("attackStrengthTicker")
    void pp$setAttackStrengthTicker(int ticks);
}
