package com.playerplayback;

import com.playerplayback.command.PlaybackCommands;
import com.playerplayback.network.ActionPayload;
import com.playerplayback.network.ServerNetworking;
import com.playerplayback.network.StatePayload;
import com.playerplayback.recording.Recorder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class PlayerPlayback implements ModInitializer {
    public static final String MOD_ID = "playerplayback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile PlaybackService service;
    private static ServerNetworking networking;

    /** The running service, or null when no server is running. */
    public static PlaybackService service() {
        return service;
    }

    @Override
    public void onInitialize() {
        //? if >=26.1 {
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        //?} else {
        /*PayloadTypeRegistry.playS2C().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
        *///?}

        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
            ServerNetworking net = networking;
            if (net != null) net.handle(payload, context.player());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PlaybackCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PlaybackService s = new PlaybackService(server);
            ServerNetworking net = new ServerNetworking(s);
            s.setChangeListener(net::broadcastState);
            networking = net;
            service = s;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlaybackService s = service;
            service = null;
            networking = null;
            if (s != null) s.shutdown();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            PlaybackService s = service;
            if (s == null) return;
            s.playback.onServerTick();
            for (Recorder r : new ArrayList<>(s.recorders())) {
                ServerPlayer p = server.getPlayerList().getPlayer(r.playerId);
                if (p != null) s.sample(p, false);
                r.sampleProjectiles();
            }
            // Keep the "recording for N seconds" display in open GUIs fresh (about once per second of real time).
            if (!s.recorders().isEmpty() && System.currentTimeMillis() / 1000 != lastGuiRefresh) {
                lastGuiRefresh = System.currentTimeMillis() / 1000;
                ServerNetworking net = networking;
                if (net != null) net.broadcastState();
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlaybackService s = service;
            if (s == null) return;
            ServerPlayer p = handler.getPlayer();
            if (s.recorderOf(p) != null) {
                PlaybackService.Result r = s.stopRecording(p.getUUID(), p);
                LOGGER.info("[Playback] {} disconnected: {}", p.getScoreboardName(), r.message());
            }
            ServerNetworking net = networking;
            if (net != null) net.unsubscribe(p.getUUID());
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            PlaybackService s = service;
            if (s == null || !(entity instanceof Projectile projectile)) return;
            if (projectile.getOwner() instanceof ServerPlayer owner) {
                Recorder r = s.recorderOf(owner);
                if (r != null) r.projectileLaunched(projectile);
            }
        });

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            PlaybackService s = service;
            if (s == null || !(player instanceof ServerPlayer sp)) return;
            Recorder r = s.recorderOf(sp);
            if (r != null) r.blockChange(pos.getX(), pos.getY(), pos.getZ(), NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()), true);
        });
    }

    private static long lastGuiRefresh;
}
