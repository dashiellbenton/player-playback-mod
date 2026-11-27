package com.playerplayback.mixin;

import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import com.playerplayback.recording.ActorEvent;
import com.playerplayback.recording.Recorder;
import com.playerplayback.util.Compat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the animation packets the server broadcasts about a recorded player (swings, crits,
 * hurt, totem, ...). This catches them no matter how they were triggered, even with nobody else online.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @Inject(method = {"sendToTrackingPlayers", "sendToTrackingPlayersAndSelf", "sendToTrackingPlayersFiltered"}, at = @At("HEAD"))
    private void pp$capture(Packet<? super ClientGamePacketListener> packet, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer player)) return;
        PlaybackService service = PlayerPlayback.service();
        if (service == null) return;
        Recorder recorder = service.recorderOf(player);
        if (recorder == null) return;
        ActorEvent event = Compat.eventFromPacket(packet, recorder.nowMs());
        if (event != null) recorder.event(event);
    }
}
