package com.playerplayback.mixin;

import com.playerplayback.playback.RealActor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Player selectors (@a, @p, @r, @e[type=player], or a player's name) only look at the online
 * player list. Real-mode actors aren't in it (so they stay out of the tab list and /list), so
 * add them here: commands then treat them like any other player.
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Shadow @Final private Function<Vec3, Vec3> position;
    @Shadow @Final private boolean currentEntity;
    @Shadow @Final private String playerName;
    @Shadow @Final private UUID entityUUID;

    @Shadow public abstract boolean isWorldLimited();
    @Shadow
    private AABB getAbsoluteAabb(Vec3 pos) {
        throw new AssertionError();
    }

    @Shadow
    private Predicate<Entity> getPredicate(Vec3 pos, AABB aabb, FeatureFlagSet features) {
        throw new AssertionError();
    }

    @Shadow
    private <T extends Entity> List<T> sortAndLimit(Vec3 pos, List<T> list) {
        throw new AssertionError();
    }

    @Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
    private void pp$includeActors(CommandSourceStack sender, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        if (currentEntity || RealActor.LIVE.isEmpty()) return;
        List<ServerPlayer> result = new ArrayList<>(cir.getReturnValue());
        if (playerName != null) {
            if (!result.isEmpty()) return;
            for (ServerPlayer p : RealActor.LIVE) {
                if (p.getGameProfile().name().equalsIgnoreCase(playerName)) {
                    cir.setReturnValue(List.of(p));
                    return;
                }
            }
            return;
        }
        if (entityUUID != null) {
            if (!result.isEmpty()) return;
            for (ServerPlayer p : RealActor.LIVE) {
                if (p.getUUID().equals(entityUUID)) {
                    cir.setReturnValue(List.of(p));
                    return;
                }
            }
            return;
        }
        Vec3 pos = position.apply(sender.getPosition());
        Predicate<Entity> predicate = getPredicate(pos, getAbsoluteAabb(pos), null);
        boolean added = false;
        for (ServerPlayer p : RealActor.LIVE) {
            if (isWorldLimited() && p.level() != sender.getLevel()) continue;
            if (!p.isRemoved() && predicate.test(p)) {
                result.add(p);
                added = true;
            }
        }
        if (added) cir.setReturnValue(sortAndLimit(pos, result));
    }
}
