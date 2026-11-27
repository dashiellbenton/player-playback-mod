package com.playerplayback.network;

import com.google.gson.Gson;
import com.playerplayback.PlaybackService;
import com.playerplayback.PlaybackService.Result;
import com.playerplayback.actor.Appearance;
import com.playerplayback.playback.Playback;
import com.playerplayback.recording.Recorder;
import com.playerplayback.scene.Scene;
import com.playerplayback.util.Compat;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Handles GUI actions and keeps open GUIs up to date. */
public final class ServerNetworking {
    private static final Gson GSON = new Gson();
    private final PlaybackService service;
    private final Set<UUID> subscribers = new HashSet<>();

    public ServerNetworking(PlaybackService service) {
        this.service = service;
    }

    public static boolean allowed(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.createCommandSourceStack().permissions());
    }

    public void handle(ActionPayload a, ServerPlayer player) {
        if (!allowed(player)) {
            player.sendSystemMessage(Component.literal("[Playback] You need operator permission to use this.").withStyle(ChatFormatting.RED));
            return;
        }
        String scene = a.arg(1).isEmpty() ? null : a.arg(1);
        Result r = switch (a.action()) {
            case "open" -> {
                subscribers.add(player.getUUID());
                sendState(player);
                yield null;
            }
            case "close" -> {
                subscribers.remove(player.getUUID());
                yield null;
            }
            case "record_start" -> service.startRecording(player, a.arg(0), a.arg(1).isEmpty() ? null : a.arg(1),
                    "true".equals(a.arg(2)), false);
            case "record_stop" -> service.stopRecording(player.getUUID(), player);
            case "play" -> service.play(a.arg(0), "true".equals(a.arg(1)), parse(a.arg(2), 1.0), "true".equals(a.arg(3)), Vec3.ZERO);
            case "play_scene" -> service.playScene(a.arg(0), "true".equals(a.arg(1)), parse(a.arg(2), 1.0), "true".equals(a.arg(3)));
            case "stop" -> service.stop((int) parse(a.arg(0), -1));
            case "stop_all" -> service.stopAll();
            case "stop_scene" -> service.stopScene(a.arg(0));
            case "pause" -> service.setPaused((int) parse(a.arg(0), -1), true);
            case "resume" -> service.setPaused((int) parse(a.arg(0), -1), false);
            case "delete" -> service.delete(a.arg(0));
            case "rename" -> service.rename(a.arg(0), a.arg(1));
            case "set_name" -> service.setName(a.arg(0), scene, a.arg(2));
            case "nametag" -> service.setNametagHidden(a.arg(0), scene,
                    switch (a.arg(2)) { case "hide" -> Boolean.TRUE; case "show" -> Boolean.FALSE; default -> null; });
            case "skin_original" -> service.resetSkin(a.arg(0), scene);
            case "skin_user" -> {
                String user = a.arg(2);
                player.sendSystemMessage(Component.literal("[Playback] Fetching skin of " + user + "...").withStyle(ChatFormatting.GRAY));
                service.fetchSkin(a.arg(0), scene, user, res -> reply(player, res));
                yield null;
            }
            case "scene_create" -> service.createScene(a.arg(0));
            case "scene_delete" -> service.deleteScene(a.arg(0));
            case "scene_add" -> service.addToScene(a.arg(0), a.arg(1), (int) Math.round(parse(a.arg(2), 0) * 1000));
            case "scene_remove" -> service.removeFromScene(a.arg(0), a.arg(1));
            default -> Result.fail("Unknown action " + a.action());
        };
        if (r != null) reply(player, r);
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void reply(ServerPlayer player, Result r) {
        player.sendSystemMessage(Component.literal("[Playback] " + r.message()).withStyle(r.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    public void unsubscribe(UUID id) {
        subscribers.remove(id);
    }

    /** Sends fresh state to everyone with the GUI open. */
    public void broadcastState() {
        if (subscribers.isEmpty()) return;
        String json = GSON.toJson(buildState());
        for (Iterator<UUID> it = subscribers.iterator(); it.hasNext(); ) {
            ServerPlayer p = service.server().getPlayerList().getPlayer(it.next());
            if (p == null) {
                it.remove();
                continue;
            }
            if (ServerPlayNetworking.canSend(p, StatePayload.TYPE)) ServerPlayNetworking.send(p, new StatePayload(json));
        }
    }

    private void sendState(ServerPlayer p) {
        if (ServerPlayNetworking.canSend(p, StatePayload.TYPE)) ServerPlayNetworking.send(p, new StatePayload(GSON.toJson(buildState())));
    }

    private GuiState buildState() {
        GuiState s = new GuiState();
        for (String name : service.recordingNames()) {
            GuiState.Rec r = new GuiState.Rec();
            r.name = name;
            Appearance a = service.appearances.get(name);
            if (a != null) {
                r.actorName = a.name;
                r.skin = a.skinSource;
                r.hideNametag = a.hideNametag;
            }
            s.recordings.add(r);
        }
        for (Scene sc : service.scenes.all()) {
            GuiState.SceneInfo info = new GuiState.SceneInfo();
            info.name = sc.name;
            for (Scene.Entry e : sc.entries) {
                GuiState.Entry ge = new GuiState.Entry();
                ge.recording = e.recording;
                ge.delaySeconds = e.delayMs / 1000.0;
                ge.actorName = e.appearance.name;
                ge.skin = e.appearance.skinSource;
                ge.hideNametag = e.appearance.hideNametag;
                info.entries.add(ge);
            }
            s.scenes.add(info);
        }
        for (Recorder r : service.recorders()) {
            GuiState.Active a = new GuiState.Active();
            ServerPlayer p = service.server().getPlayerList().getPlayer(r.playerId);
            a.player = p == null ? "?" : Compat.profileName(p.getGameProfile());
            a.recording = r.name;
            a.seconds = r.nowMs() / 1000.0;
            s.recordingNow.add(a);
        }
        for (Playback p : service.playback.list()) {
            GuiState.Play g = new GuiState.Play();
            g.id = p.id;
            g.label = p.label;
            g.scene = p.sceneName;
            g.actors = p.actors.size();
            g.paused = p.paused;
            g.loop = p.loop;
            s.playing.add(g);
        }
        return s;
    }
}
