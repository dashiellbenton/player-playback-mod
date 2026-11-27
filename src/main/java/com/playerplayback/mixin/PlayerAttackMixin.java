package com.playerplayback.mixin;

import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import com.playerplayback.recording.ActorEvent;
import com.playerplayback.recording.Recorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records melee attacks so real-mode actors can repeat them for real. */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void pp$recordAttack(Entity target, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        PlaybackService service = PlayerPlayback.service();
        if (service == null) return;
        Recorder recorder = service.recorderOf(player);
        if (recorder == null) return;
        float strength = player.getAttackStrengthScale(0.5f);
        boolean crit = strength > 0.9f && player.fallDistance > 0 && !player.onGround() && !player.onClimbable()
                && !player.isInWater() && !player.isPassenger() && !player.isSprinting();
        recorder.event(new ActorEvent.Attack(recorder.nowMs(), target.getX(), target.getY(), target.getZ(), strength, crit));
    }
}
