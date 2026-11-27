package com.playerplayback.mixin;

import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records sounds made by recorded players (hits, pearl throws, eating, hurt...). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSoundMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void pp$positional(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
                               float volume, float pitch, long seed, CallbackInfo ci) {
        PlaybackService s = PlayerPlayback.service();
        if (s != null) s.soundPlayed((ServerLevel) (Object) this, except, null, x, y, z, sound, source, volume, pitch, seed);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void pp$entity(Entity except, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source,
                           float volume, float pitch, long seed, CallbackInfo ci) {
        PlaybackService s = PlayerPlayback.service();
        if (s != null) s.soundPlayed((ServerLevel) (Object) this, except, sourceEntity,
                sourceEntity.getX(), sourceEntity.getY(), sourceEntity.getZ(), sound, source, volume, pitch, seed);
    }
}
