package com.playerplayback.mixin;

import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import com.playerplayback.recording.Recorder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records blocks placed by a recorded player (only when the recording has block replay on). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    private void pp$recordPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !cir.getReturnValue().consumesAction()) return;
        PlaybackService service = PlayerPlayback.service();
        if (service == null) return;
        Recorder recorder = service.recorderOf(player);
        if (recorder == null || !recorder.recordBlocks) return;
        BlockPos pos = context.getClickedPos();
        recorder.blockChange(pos.getX(), pos.getY(), pos.getZ(), NbtUtils.writeBlockState(context.getLevel().getBlockState(pos)), false);
    }
}
