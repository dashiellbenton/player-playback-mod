package com.playerplayback.mixin;

import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Samples recorded players whenever the server handles their movement/input. These packets are
 * processed in real time between ticks, so recordings keep full detail at any /tick rate.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = {"handleMovePlayer", "handlePlayerInput", "handlePlayerCommand"}, at = @At("TAIL"))
    private void pp$sample(CallbackInfo ci) {
        PlaybackService service = PlayerPlayback.service();
        if (service != null) service.sample(player, false);
    }
}
