package com.playerplayback.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.playerplayback.PlayerPlayback;
import com.playerplayback.PlaybackService;
import com.playerplayback.PlaybackService.Result;
import com.playerplayback.playback.Playback;
import com.playerplayback.recording.Recorder;
import com.playerplayback.scene.Scene;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /playback} (alias {@code /pb}). Requires permission level 2 (game master).
 */
public final class PlaybackCommands {
    private PlaybackCommands() {}

    private static final SuggestionProvider<CommandSourceStack> RECORDINGS = (ctx, b) ->
            SharedSuggestionProvider.suggest(svc().recordingNames(), b);
    private static final SuggestionProvider<CommandSourceStack> SCENES = (ctx, b) ->
            SharedSuggestionProvider.suggest(svc().scenes.all().stream().map(s -> s.name), b);
    private static final SuggestionProvider<CommandSourceStack> PLAYBACK_IDS = (ctx, b) ->
            SharedSuggestionProvider.suggest(svc().playback.list().stream().map(p -> String.valueOf(p.id)), b);
    private static final SuggestionProvider<CommandSourceStack> SCENE_RECORDINGS = (ctx, b) -> {
        Scene s = svc().scenes.get(StringArgumentType.getString(ctx, "scene"));
        return s == null ? b.buildFuture() : SharedSuggestionProvider.suggest(s.entries.stream().map(e -> e.recording), b);
    };

    private static PlaybackService svc() {
        return PlayerPlayback.service();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(literal("playback")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(recordCommand())
                .then(literal("play")
                        .then(argument("recording", StringArgumentType.word()).suggests(RECORDINGS)
                                .executes(c -> play(c, false, 1.0, false))
                                .then(argument("loop", BoolArgumentType.bool())
                                        .executes(c -> play(c, BoolArgumentType.getBool(c, "loop"), 1.0, false))
                                        .then(argument("speed", DoubleArgumentType.doubleArg(0.05, 10))
                                                .executes(c -> play(c, BoolArgumentType.getBool(c, "loop"), DoubleArgumentType.getDouble(c, "speed"), false))
                                                .then(literal("gameclock")
                                                        .executes(c -> play(c, BoolArgumentType.getBool(c, "loop"), DoubleArgumentType.getDouble(c, "speed"), true)))))))
                .then(literal("stop")
                        .executes(c -> reply(c, svc().stopAll()))
                        .then(literal("all").executes(c -> reply(c, svc().stopAll())))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(PLAYBACK_IDS)
                                .executes(c -> reply(c, svc().stop(IntegerArgumentType.getInteger(c, "id"))))))
                .then(literal("pause")
                        .executes(c -> reply(c, svc().setAllPaused(true)))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(PLAYBACK_IDS)
                                .executes(c -> reply(c, svc().setPaused(IntegerArgumentType.getInteger(c, "id"), true)))))
                .then(literal("resume")
                        .executes(c -> reply(c, svc().setAllPaused(false)))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(PLAYBACK_IDS)
                                .executes(c -> reply(c, svc().setPaused(IntegerArgumentType.getInteger(c, "id"), false)))))
                .then(literal("list").executes(PlaybackCommands::list))
                .then(literal("delete")
                        .then(argument("recording", StringArgumentType.word()).suggests(RECORDINGS)
                                .executes(c -> reply(c, svc().delete(StringArgumentType.getString(c, "recording"))))))
                .then(literal("rename")
                        .then(argument("recording", StringArgumentType.word()).suggests(RECORDINGS)
                                .then(argument("newName", StringArgumentType.word())
                                        .executes(c -> reply(c, svc().rename(StringArgumentType.getString(c, "recording"),
                                                StringArgumentType.getString(c, "newName")))))))
                .then(literal("actor")
                        .then(appearanceArgs(argument("recording", StringArgumentType.word()).suggests(RECORDINGS), c -> null)))
                .then(sceneCommand()));

        dispatcher.register(literal("pb").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).redirect(root));
    }

    // ------------------------------------------------------------------ record

    private static LiteralArgumentBuilder<CommandSourceStack> recordCommand() {
        return literal("record")
                .then(literal("start")
                        .then(argument("name", StringArgumentType.word())
                                .executes(c -> startRec(c, self(c), null, false))
                                .then(literal("blocks").executes(c -> startRec(c, self(c), null, true)))
                                .then(literal("scene")
                                        .then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                                                .executes(c -> startRec(c, self(c), StringArgumentType.getString(c, "scene"), false))
                                                .then(literal("blocks").executes(c -> startRec(c, self(c), StringArgumentType.getString(c, "scene"), true)))))
                                .then(argument("player", EntityArgument.player())
                                        .executes(c -> startRec(c, EntityArgument.getPlayer(c, "player"), null, false))
                                        .then(literal("blocks").executes(c -> startRec(c, EntityArgument.getPlayer(c, "player"), null, true)))
                                        .then(literal("scene")
                                                .then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                                                        .executes(c -> startRec(c, EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "scene"), false))
                                                        .then(literal("blocks").executes(c -> startRec(c, EntityArgument.getPlayer(c, "player"), StringArgumentType.getString(c, "scene"), true))))))))
                .then(literal("stop")
                        .executes(c -> {
                            ServerPlayer p = self(c);
                            return reply(c, svc().stopRecording(p.getUUID(), p));
                        })
                        .then(literal("all").executes(c -> {
                            int n = 0;
                            for (Recorder r : List.copyOf(svc().recorders())) {
                                svc().stopRecording(r.playerId, c.getSource().getServer().getPlayerList().getPlayer(r.playerId));
                                n++;
                            }
                            return reply(c, Result.ok("Stopped " + n + " recording(s)"));
                        }))
                        .then(argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            return reply(c, svc().stopRecording(p.getUUID(), p));
                        })));
    }

    private static int startRec(CommandContext<CommandSourceStack> c, ServerPlayer player, String scene, boolean blocks) {
        return reply(c, svc().startRecording(player, StringArgumentType.getString(c, "name"), scene, blocks, false));
    }

    private static ServerPlayer self(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    private static int play(CommandContext<CommandSourceStack> c, boolean loop, double speed, boolean gameClock) {
        return reply(c, svc().play(StringArgumentType.getString(c, "recording"), loop, speed, gameClock, Vec3.ZERO));
    }

    // ------------------------------------------------------------------ appearance

    /** name / skin / nametag sub-commands, shared by "/pb actor" and "/pb scene actor". */
    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T appearanceArgs(
            T node, Function<CommandContext<CommandSourceStack>, String> sceneOf) {
        return node
                .then(literal("name")
                        .then(literal("reset").executes(c -> reply(c, svc().setName(rec(c), sceneOf.apply(c), null))))
                        .then(argument("name", StringArgumentType.word())
                                .executes(c -> reply(c, svc().setName(rec(c), sceneOf.apply(c), StringArgumentType.getString(c, "name"))))))
                .then(literal("skin")
                        .then(literal("player").then(argument("username", StringArgumentType.word()).executes(c -> {
                            String user = StringArgumentType.getString(c, "username");
                            c.getSource().sendSuccess(() -> Component.literal("Fetching skin of " + user + "...").withStyle(ChatFormatting.GRAY), false);
                            svc().fetchSkin(rec(c), sceneOf.apply(c), user, r -> send(c.getSource(), r));
                            return 1;
                        })))
                        .then(literal("copy").then(argument("player", EntityArgument.player())
                                .executes(c -> reply(c, svc().copySkin(rec(c), sceneOf.apply(c), EntityArgument.getPlayer(c, "player"))))))
                        .then(literal("original").executes(c -> reply(c, svc().resetSkin(rec(c), sceneOf.apply(c))))))
                .then(literal("nametag")
                        .then(literal("show").executes(c -> reply(c, svc().setNametagHidden(rec(c), sceneOf.apply(c), false))))
                        .then(literal("hide").executes(c -> reply(c, svc().setNametagHidden(rec(c), sceneOf.apply(c), true))))
                        .then(literal("reset").executes(c -> reply(c, svc().setNametagHidden(rec(c), sceneOf.apply(c), null)))));
    }

    private static String rec(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "recording");
    }

    // ------------------------------------------------------------------ scenes

    private static LiteralArgumentBuilder<CommandSourceStack> sceneCommand() {
        RequiredArgumentBuilder<CommandSourceStack, String> sceneArg = argument("scene", StringArgumentType.word()).suggests(SCENES);
        return literal("scene")
                .then(literal("list").executes(PlaybackCommands::listScenes))
                .then(literal("create").then(argument("scene", StringArgumentType.word())
                        .executes(c -> reply(c, svc().createScene(scene(c))))))
                .then(literal("delete").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .executes(c -> reply(c, svc().deleteScene(scene(c))))))
                .then(literal("info").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .executes(PlaybackCommands::sceneInfo)))
                .then(literal("add").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .then(argument("recording", StringArgumentType.word()).suggests(RECORDINGS)
                                .executes(c -> reply(c, svc().addToScene(scene(c), rec(c), 0)))
                                .then(argument("delaySeconds", DoubleArgumentType.doubleArg(0))
                                        .executes(c -> reply(c, svc().addToScene(scene(c), rec(c),
                                                (int) Math.round(DoubleArgumentType.getDouble(c, "delaySeconds") * 1000))))))))
                .then(literal("remove").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .then(argument("recording", StringArgumentType.word()).suggests(SCENE_RECORDINGS)
                                .executes(c -> reply(c, svc().removeFromScene(scene(c), rec(c)))))))
                .then(literal("offset").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .then(argument("recording", StringArgumentType.word()).suggests(SCENE_RECORDINGS)
                                .then(argument("dx", DoubleArgumentType.doubleArg())
                                        .then(argument("dy", DoubleArgumentType.doubleArg())
                                                .then(argument("dz", DoubleArgumentType.doubleArg())
                                                        .executes(c -> reply(c, svc().setSceneOffset(scene(c), rec(c), new Vec3(
                                                                DoubleArgumentType.getDouble(c, "dx"), DoubleArgumentType.getDouble(c, "dy"),
                                                                DoubleArgumentType.getDouble(c, "dz")))))))))))
                .then(literal("play").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .executes(c -> reply(c, svc().playScene(scene(c), false, 1.0, false)))
                        .then(argument("loop", BoolArgumentType.bool())
                                .executes(c -> reply(c, svc().playScene(scene(c), BoolArgumentType.getBool(c, "loop"), 1.0, false)))
                                .then(argument("speed", DoubleArgumentType.doubleArg(0.05, 10))
                                        .executes(c -> reply(c, svc().playScene(scene(c), BoolArgumentType.getBool(c, "loop"), DoubleArgumentType.getDouble(c, "speed"), false)))
                                        .then(literal("gameclock")
                                                .executes(c -> reply(c, svc().playScene(scene(c), BoolArgumentType.getBool(c, "loop"), DoubleArgumentType.getDouble(c, "speed"), true))))))))
                .then(literal("stop").then(argument("scene", StringArgumentType.word()).suggests(SCENES)
                        .executes(c -> reply(c, svc().stopScene(scene(c))))))
                .then(literal("actor").then(sceneArg
                        .then(appearanceArgs(argument("recording", StringArgumentType.word()).suggests(SCENE_RECORDINGS), PlaybackCommands::scene))));
    }

    private static String scene(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "scene");
    }

    // ------------------------------------------------------------------ listing

    private static int list(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        PlaybackService s = svc();
        src.sendSuccess(() -> Component.literal("Recordings (" + s.recordingNames().size() + "):").withStyle(ChatFormatting.GOLD), false);
        for (String name : s.recordingNames()) src.sendSuccess(() -> Component.literal(" - " + name), false);
        if (!s.recorders().isEmpty()) {
            src.sendSuccess(() -> Component.literal("Recording now:").withStyle(ChatFormatting.RED), false);
            for (Recorder r : s.recorders()) {
                src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " - %s (%.0fs)", r.name, r.nowMs() / 1000.0)), false);
            }
        }
        List<Playback> playing = s.playback.list();
        if (!playing.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Playing:").withStyle(ChatFormatting.GREEN), false);
            for (Playback p : playing) {
                src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " #%d %s%s - %d actor(s) %.0f/%.0fs%s%s",
                        p.id, p.sceneName != null ? "scene " : "", p.label, p.actors.size(), p.elapsedMs() / 1000.0, p.lengthMs() / 1000.0,
                        p.loop ? " loop" : "", p.paused ? " [paused]" : "")), false);
            }
        }
        return 1;
    }

    private static int listScenes(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        src.sendSuccess(() -> Component.literal("Scenes:").withStyle(ChatFormatting.GOLD), false);
        for (Scene s : svc().scenes.all()) {
            src.sendSuccess(() -> Component.literal(" - " + s.name + " (" + s.entries.size() + " recordings)"), false);
        }
        return 1;
    }

    private static int sceneInfo(CommandContext<CommandSourceStack> c) {
        Scene s = svc().scenes.get(scene(c));
        if (s == null) return reply(c, Result.fail("Unknown scene"));
        CommandSourceStack src = c.getSource();
        src.sendSuccess(() -> Component.literal("Scene " + s.name + ":").withStyle(ChatFormatting.GOLD), false);
        for (Scene.Entry e : s.entries) {
            StringBuilder sb = new StringBuilder(" - ").append(e.recording).append(" @ ").append(e.delayMs / 1000.0).append("s");
            if (e.offsetX != 0 || e.offsetY != 0 || e.offsetZ != 0) sb.append(String.format(Locale.ROOT, " offset %.1f %.1f %.1f", e.offsetX, e.offsetY, e.offsetZ));
            if (e.appearance.name != null) sb.append(" name=").append(e.appearance.name);
            if (e.appearance.skinSource != null) sb.append(" skin=").append(e.appearance.skinSource);
            if (e.appearance.hideNametag != null) sb.append(e.appearance.hideNametag ? " nametag=hidden" : " nametag=shown");
            src.sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static int reply(CommandContext<CommandSourceStack> c, Result r) {
        send(c.getSource(), r);
        return r.ok() ? 1 : 0;
    }

    private static void send(CommandSourceStack src, Result r) {
        if (r.ok()) src.sendSuccess(() -> Component.literal(r.message()).withStyle(ChatFormatting.GREEN), true);
        else src.sendFailure(Component.literal(r.message()));
    }
}
