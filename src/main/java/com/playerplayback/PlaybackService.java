package com.playerplayback;

import com.playerplayback.actor.Appearance;
import com.playerplayback.actor.AppearanceStore;
import com.playerplayback.actor.SkinFetcher;
import com.playerplayback.playback.Actor;
import com.playerplayback.playback.Playback;
import com.playerplayback.playback.PlaybackManager;
import com.playerplayback.recording.Recorder;
import com.playerplayback.recording.Recording;
import com.playerplayback.scene.Scene;
import com.playerplayback.scene.SceneStore;
import com.playerplayback.scene.Formation;
import com.playerplayback.util.Compat;
import com.playerplayback.util.Names;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * All mod operations. Commands and the GUI both call into this class (always on the server
 * thread), so they behave identically.
 */
public final class PlaybackService {
    /** Result of an operation, shown to the user. */
    public record Result(boolean ok, String message) {
        public static Result ok(String msg) { return new Result(true, msg); }
        public static Result fail(String msg) { return new Result(false, msg); }
    }

    private final MinecraftServer server;
    private final Path recordingsDir;
    private final TreeSet<String> recordingNames = new TreeSet<>();
    private final Map<String, Recording> cache = new HashMap<>();
    private final Map<UUID, Recorder> recorders = new HashMap<>();
    public final AppearanceStore appearances;
    public final SceneStore scenes;
    public final PlaybackManager playback;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Player Playback IO");
        t.setDaemon(true);
        return t;
    });
    private Runnable changeListener = () -> {};

    public PlaybackService(MinecraftServer server) {
        this.server = server;
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("playerplayback");
        this.recordingsDir = root.resolve("recordings");
        this.appearances = new AppearanceStore(root.resolve("appearances.json"));
        this.scenes = new SceneStore(root.resolve("scenes"));
        this.playback = new PlaybackManager(server);
        this.playback.setChangeListener(() -> changeListener.run());
        scanRecordings();
    }

    public void setChangeListener(Runnable r) {
        this.changeListener = r;
    }

    public MinecraftServer server() {
        return server;
    }

    private void changed() {
        changeListener.run();
    }

    private void scanRecordings() {
        if (!Files.isDirectory(recordingsDir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(recordingsDir, "*.pprec")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                recordingNames.add(n.substring(0, n.length() - ".pprec".length()));
            }
        } catch (IOException e) {
            PlayerPlayback.LOGGER.error("Failed to list recordings", e);
        }
    }

    private Path fileOf(String name) {
        return recordingsDir.resolve(name + ".pprec");
    }

    public SortedSet<String> recordingNames() {
        return Collections.unmodifiableSortedSet(recordingNames);
    }

    public Recording load(String name) throws IOException {
        Recording r = cache.get(name);
        if (r == null) {
            r = Recording.read(fileOf(name));
            cache.put(name, r);
        }
        return r;
    }

    public void shutdown() {
        // Save any recording still in progress.
        for (UUID id : new ArrayList<>(recorders.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            stopRecording(id, p);
        }
        playback.shutdown();
        io.shutdown();
        try {
            io.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ recording

    public Recorder recorderOf(ServerPlayer player) {
        return recorders.get(player.getUUID());
    }

    public Collection<Recorder> recorders() {
        return recorders.values();
    }

    public Result startRecording(ServerPlayer player, String name, String scene, boolean blocks, boolean gameClock) {
        if (!Names.valid(name)) return Result.fail("Invalid name. Use letters, numbers, _ - . (max 48)");
        if (recorders.containsKey(player.getUUID())) return Result.fail(player.getScoreboardName() + " is already being recorded");
        for (Recorder r : recorders.values()) if (r.name.equals(name)) return Result.fail("Another player is recording '" + name + "'");
        Scene sc = null;
        if (scene != null) {
            sc = scenes.get(scene);
            if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        }
        Recorder recorder = new Recorder(name, player, blocks, scene);
        recorders.put(player.getUUID(), recorder);
        String extra = "";
        if (sc != null && !sc.entries.isEmpty()) {
            Result r = playScene(scene, false, 1.0, gameClock, recorder);
            extra = r.ok() ? " while playing scene '" + scene + "'" : " (scene failed: " + r.message() + ")";
        }
        changed();
        return Result.ok("Recording '" + name + "' of " + player.getScoreboardName() + extra + ". Stop with /pb record stop");
    }

    /** Called from mixins/events on the server thread. */
    public void sample(ServerPlayer player, boolean force) {
        Recorder r = recorders.get(player.getUUID());
        if (r != null) {
            r.sample(player, force);
            if (r.isFull()) {
                stopRecording(player.getUUID(), player);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Playback] Recording reached the 2 hour limit and was saved."));
            }
        }
    }

    /** Called for every sound the server plays; keeps the ones made by recorded players. */
    public void soundPlayed(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity except,
                            net.minecraft.world.entity.Entity sourceEntity, double x, double y, double z,
                            net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source,
                            float volume, float pitch, long seed) {
        if (recorders.isEmpty()) return;
        for (Recorder r : recorders.values()) {
            ServerPlayer p = server.getPlayerList().getPlayer(r.playerId);
            if (p == null || p.level() != level) continue;
            boolean mine = except == p || sourceEntity == p || (sourceEntity == null && p.distanceToSqr(x, y, z) < 1.0);
            if (mine) {
                r.sound(sound.value().location().toString(), source.getName(), x, y, z, volume, pitch, seed);
                return;
            }
        }
    }

    public Result stopRecording(UUID playerId, ServerPlayer player) {
        Recorder r = recorders.remove(playerId);
        if (r == null) return Result.fail("That player is not being recorded");
        if (r.scenePlaybackId >= 0) playback.stop(r.scenePlaybackId);
        Recording rec = r.finish(player);
        String name = r.name;
        cache.put(name, rec);
        recordingNames.add(name);
        Path file = fileOf(name);
        io.execute(() -> {
            try {
                rec.write(file);
            } catch (IOException e) {
                PlayerPlayback.LOGGER.error("Failed to save recording {}", name, e);
            }
        });
        String extra = "";
        if (r.scene != null) {
            Scene sc = scenes.get(r.scene);
            if (sc != null) {
                if (sc.entries.stream().noneMatch(e -> e.recording.equals(name))) sc.entries.add(new Scene.Entry(sc.newId(name), name, 0));
                scenes.save(sc);
                extra = " and added to scene '" + r.scene + "'";
            }
        }
        changed();
        return Result.ok(String.format(Locale.ROOT, "Saved '%s' (%.1fs, %d frames)%s", name, rec.durationMs / 1000.0, rec.frames.size(), extra));
    }

    public Result delete(String name) {
        if (!recordingNames.remove(name)) return Result.fail("Unknown recording '" + name + "'");
        cache.remove(name);
        appearances.remove(name);
        scenes.recordingDeleted(name);
        Path file = fileOf(name);
        io.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                PlayerPlayback.LOGGER.error("Failed to delete {}", file, e);
            }
        });
        changed();
        return Result.ok("Deleted '" + name + "'");
    }

    public Result rename(String from, String to) {
        if (!recordingNames.contains(from)) return Result.fail("Unknown recording '" + from + "'");
        if (!Names.valid(to)) return Result.fail("Invalid name");
        if (recordingNames.contains(to)) return Result.fail("'" + to + "' already exists");
        Path a = fileOf(from), b = fileOf(to);
        io.execute(() -> {
            try {
                Files.move(a, b);
            } catch (IOException e) {
                PlayerPlayback.LOGGER.error("Failed to rename {}", a, e);
            }
        });
        recordingNames.remove(from);
        recordingNames.add(to);
        Recording r = cache.remove(from);
        if (r != null) cache.put(to, r);
        appearances.rename(from, to);
        scenes.recordingRenamed(from, to);
        changed();
        return Result.ok("Renamed '" + from + "' to '" + to + "'");
    }

    // ------------------------------------------------------------------ playback

    private Appearance recordingAppearance(String recording) {
        Appearance a = appearances.get(recording);
        return a == null ? new Appearance() : a.copy();
    }

    public Result play(String name, boolean loop, double speed, boolean gameClock, Vec3 offset) {
        if (!recordingNames.contains(name)) return Result.fail("Unknown recording '" + name + "'");
        Recording rec;
        try {
            rec = load(name);
        } catch (IOException e) {
            return Result.fail("Could not read '" + name + "': " + e.getMessage());
        }
        boolean blocks = rec.events.stream().anyMatch(e -> e instanceof com.playerplayback.recording.ActorEvent.Block);
        Playback p = playback.create(name, null, loop, clampSpeed(speed), gameClock, blocks);
        p.actors.add(new Actor(name, null, rec, recordingAppearance(name), 0, 1.0, offset == null ? Vec3.ZERO : offset, server.registryAccess()));
        playback.start(p);
        return Result.ok("Playing '" + name + "' (#" + p.id + ")");
    }

    public Result playScene(String sceneName, boolean loop, double speed, boolean gameClock) {
        return playScene(sceneName, loop, speed, gameClock, null);
    }

    private Result playScene(String sceneName, boolean loop, double speed, boolean gameClock, Recorder forRecorder) {
        Scene scene = scenes.get(sceneName);
        if (scene == null) return Result.fail("Unknown scene '" + sceneName + "'");
        if (scene.entries.isEmpty()) return Result.fail("Scene '" + sceneName + "' is empty");
        Playback p = playback.create(sceneName, sceneName, loop, clampSpeed(speed), gameClock, true);
        List<String> missing = new ArrayList<>();
        for (Scene.Entry e : scene.entries) {
            if (!recordingNames.contains(e.recording)) {
                missing.add(e.recording);
                continue;
            }
            // Don't play the take that is currently being re-recorded.
            if (forRecorder != null && forRecorder.name.equals(e.recording)) continue;
            try {
                Recording rec = load(e.recording);
                Appearance app = e.appearance.over(recordingAppearance(e.recording));
                p.actors.add(new Actor(e.recording, e.id, rec, app, e.delayMs, e.speed, new Vec3(e.offsetX, e.offsetY, e.offsetZ), server.registryAccess()));
            } catch (IOException ex) {
                missing.add(e.recording);
            }
        }
        if (p.actors.isEmpty()) return Result.fail("Nothing to play in scene '" + sceneName + "'");
        playback.start(p);
        if (forRecorder != null) forRecorder.scenePlaybackId = p.id;
        String msg = "Playing scene '" + sceneName + "' with " + p.actors.size() + " actors (#" + p.id + ")";
        if (!missing.isEmpty()) msg += ". Missing: " + String.join(", ", missing);
        return Result.ok(msg);
    }

    private static double clampSpeed(double s) {
        return Math.max(0.05, Math.min(10.0, s));
    }

    // ------------------------------------------------------------------ appearance

    /** Applies {@code edit} to the recording-level (scene == null) or scene-entry appearance. */
    /**
     * Applies {@code edit} to a recording's default appearance (scene == null), or to one scene
     * entry ({@code recording} is then the entry id, e.g. "take1+3"; use "*" for every entry).
     */
    private Result editAppearance(String recording, String scene, Consumer<Appearance> edit, String message) {
        if (scene != null) {
            Scene sc = scenes.get(scene);
            if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
            if (recording.equals("*")) {
                for (Scene.Entry e : sc.entries) edit.accept(e.appearance);
                scenes.save(sc);
                for (String r : new HashSet<>(sc.entries.stream().map(e -> e.recording).toList())) refreshRunning(r);
                changed();
                return Result.ok(message + " (" + sc.entries.size() + " actors)");
            }
            Scene.Entry entry = sc.find(recording);
            if (entry == null) return Result.fail("'" + recording + "' is not in scene '" + scene + "'");
            edit.accept(entry.appearance);
            scenes.save(sc);
            recording = entry.recording;
        } else {
            if (!recordingNames.contains(recording)) return Result.fail("Unknown recording '" + recording + "'");
            edit.accept(appearances.getOrCreate(recording));
            appearances.save();
        }
        refreshRunning(recording);
        changed();
        return Result.ok(message);
    }

    private void refreshRunning(String recording) {
        playback.refreshActors(recording, actor -> {
            Appearance base = recordingAppearance(recording);
            if (actor.entryId != null) {
                for (Playback p : playback.list()) {
                    if (p.sceneName != null && p.actors.contains(actor)) {
                        Scene sc = scenes.get(p.sceneName);
                        Scene.Entry e = sc == null ? null : sc.find(actor.entryId);
                        if (e != null) return e.appearance.over(base);
                    }
                }
            }
            return base;
        });
    }

    public Result setName(String recording, String scene, String name) {
        String n = name == null || name.isBlank() ? null : name;
        if (n != null && n.length() > 16) return Result.fail("Names can be at most 16 characters");
        return editAppearance(recording, scene, a -> a.name = n, n == null ? "Name reset" : "Name set to " + n);
    }

    public Result setNametagHidden(String recording, String scene, Boolean hidden) {
        return editAppearance(recording, scene, a -> a.hideNametag = hidden,
                hidden == null ? "Nametag reset" : hidden ? "Nametag hidden" : "Nametag shown");
    }

    public Result resetSkin(String recording, String scene) {
        return editAppearance(recording, scene, a -> a.setSkin(null), "Skin reset to the recorded player's skin");
    }

    public Result copySkin(String recording, String scene, ServerPlayer from) {
        Property tex = Compat.texturesOf(from.getGameProfile());
        if (tex == null) return Result.fail(from.getScoreboardName() + " has no skin data (offline-mode server?)");
        SkinFetcher.Skin skin = new SkinFetcher.Skin(tex.value(), tex.signature(), from.getScoreboardName());
        return editAppearance(recording, scene, a -> a.setSkin(skin), "Skin copied from " + from.getScoreboardName());
    }

    /** Fetches a skin from Mojang, then reports back through {@code callback} on the server thread. */
    public void fetchSkin(String recording, String scene, String username, Consumer<Result> callback) {
        if (scene == null && !recordingNames.contains(recording)) {
            callback.accept(Result.fail("Unknown recording '" + recording + "'"));
            return;
        }
        CompletableFuture<SkinFetcher.Skin> f = SkinFetcher.fetch(username);
        f.whenComplete((skin, err) -> server.execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                callback.accept(Result.fail("Couldn't get skin of " + username + ": " + cause.getMessage()));
            } else {
                callback.accept(editAppearance(recording, scene, a -> a.setSkin(skin), "Skin set to " + skin.source() + "'s skin"));
            }
        }));
    }

    // ------------------------------------------------------------------ scenes

    public Result createScene(String name) {
        if (!Names.valid(name)) return Result.fail("Invalid scene name");
        if (scenes.get(name) != null) return Result.fail("Scene '" + name + "' already exists");
        scenes.create(name);
        changed();
        return Result.ok("Created scene '" + name + "'");
    }

    public Result deleteScene(String name) {
        if (scenes.get(name) == null) return Result.fail("Unknown scene '" + name + "'");
        playback.stopScene(name);
        scenes.delete(name);
        changed();
        return Result.ok("Deleted scene '" + name + "'");
    }

    public Result addToScene(String scene, String recording, int delayMs) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        if (!recordingNames.contains(recording)) return Result.fail("Unknown recording '" + recording + "'");
        Scene.Entry e = new Scene.Entry(sc.newId(recording), recording, delayMs);
        sc.entries.add(e);
        scenes.save(sc);
        changed();
        return Result.ok("Added " + e.id + " to '" + scene + "', starting at " + delayMs / 1000.0 + "s");
    }

    /** Removes one entry by id, or every copy of a recording when given the bare recording name with "all". */
    public Result removeFromScene(String scene, String recording) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        boolean removed = recording.endsWith("*")
                ? sc.entries.removeIf(e -> e.recording.equals(recording.substring(0, recording.length() - 1)))
                : sc.entries.removeIf(e -> e.id.equals(recording));
        if (!removed) return Result.fail("'" + recording + "' is not in that scene");
        scenes.save(sc);
        changed();
        return Result.ok("Removed " + recording + " from '" + scene + "'");
    }

    public Result setSceneOffset(String scene, String recording, Vec3 offset) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        Scene.Entry e = sc.find(recording);
        if (e == null) return Result.fail("'" + recording + "' is not in that scene");
        e.offsetX = offset.x;
        e.offsetY = offset.y;
        e.offsetZ = offset.z;
        scenes.save(sc);
        changed();
        return Result.ok(String.format(Locale.ROOT, "%s is now offset by %.1f %.1f %.1f in '%s'", recording, offset.x, offset.y, offset.z, scene));
    }

    public Result setSceneDelay(String scene, String entryId, int delayMs) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        Scene.Entry e = sc.find(entryId);
        if (e == null) return Result.fail("'" + entryId + "' is not in that scene");
        e.delayMs = delayMs;
        scenes.save(sc);
        changed();
        return Result.ok(entryId + " now starts at " + delayMs / 1000.0 + "s");
    }

    public Result clearScene(String scene) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        int n = sc.entries.size();
        sc.entries.clear();
        scenes.save(sc);
        changed();
        return Result.ok("Removed " + n + " actors from '" + scene + "'");
    }

    // ------------------------------------------------------------------ crowds

    /**
     * Adds {@code count} copies of a recording to a scene, laid out in a formation around the
     * original path, each with slight timing/speed/position variation so the crowd looks natural.
     */
    public Result addCrowd(String scene, String recording, int count, Formation formation, double spacing, double jitterSeconds) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        if (!recordingNames.contains(recording)) return Result.fail("Unknown recording '" + recording + "'");
        if (count < 1 || count > 1000) return Result.fail("Count must be 1-1000");
        float yaw = 0;
        try {
            var first = load(recording).firstFrame();
            if (first != null) yaw = first.yaw();
        } catch (IOException e) {
            return Result.fail("Could not read '" + recording + "': " + e.getMessage());
        }
        Random random = new Random();
        // If the take is already in the scene at its original spot, don't stack a copy on top of it.
        boolean originalTaken = sc.entries.stream().anyMatch(e -> e.recording.equals(recording)
                && e.offsetX == 0 && e.offsetY == 0 && e.offsetZ == 0);
        List<Vec3> offsets = new ArrayList<>(formation.layout(originalTaken ? count + 1 : count, spacing, yaw, random));
        if (originalTaken) offsets.remove(0);
        for (Vec3 off : offsets) {
            Scene.Entry e = new Scene.Entry(sc.newId(recording), recording, (int) Math.round(random.nextDouble() * jitterSeconds * 1000));
            double noise = formation == Formation.RANDOM ? 0 : spacing * 0.15;
            e.offsetX = off.x + (random.nextDouble() * 2 - 1) * noise;
            e.offsetY = off.y;
            e.offsetZ = off.z + (random.nextDouble() * 2 - 1) * noise;
            e.speed = jitterSeconds > 0 ? 0.95 + random.nextDouble() * 0.1 : 1.0;
            sc.entries.add(e);
        }
        scenes.save(sc);
        changed();
        return Result.ok("Added a " + formation.name().toLowerCase(Locale.ROOT) + " crowd of " + count + " x " + recording
                + " to '" + scene + "' (" + sc.entries.size() + " actors total)");
    }

    /** Gives every actor in a scene a skin from the list (round-robin, fetched from Mojang). */
    public void crowdSkins(String scene, List<String> usernames, Consumer<Result> callback) {
        Scene sc = scenes.get(scene);
        if (sc == null) {
            callback.accept(Result.fail("Unknown scene '" + scene + "'"));
            return;
        }
        if (usernames.isEmpty()) {
            callback.accept(Result.fail("Give at least one username"));
            return;
        }
        List<CompletableFuture<SkinFetcher.Skin>> futures = usernames.stream().map(SkinFetcher::fetch).toList();
        CompletableFuture.allOf(futures.stream().map(f -> f.exceptionally(t -> null)).toArray(CompletableFuture[]::new))
                .whenComplete((v, err) -> server.execute(() -> {
                    List<SkinFetcher.Skin> skins = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    for (int i = 0; i < futures.size(); i++) {
                        SkinFetcher.Skin skin = futures.get(i).getNow(null);
                        if (skin == null) failed.add(usernames.get(i));
                        else skins.add(skin);
                    }
                    if (skins.isEmpty()) {
                        callback.accept(Result.fail("Couldn't fetch any of those skins"));
                        return;
                    }
                    Scene current = scenes.get(scene);
                    if (current == null) return;
                    int i = 0;
                    for (Scene.Entry e : current.entries) e.appearance.setSkin(skins.get(i++ % skins.size()));
                    scenes.save(current);
                    for (String r : new HashSet<>(current.entries.stream().map(e -> e.recording).toList())) refreshRunning(r);
                    changed();
                    String msg = "Gave " + current.entries.size() + " actors " + skins.size() + " different skins";
                    if (!failed.isEmpty()) msg += " (not found: " + String.join(", ", failed) + ")";
                    callback.accept(Result.ok(msg));
                }));
    }

    /** Names every actor in a scene prefix1, prefix2, ... ("" resets names). */
    public Result crowdNames(String scene, String prefix) {
        Scene sc = scenes.get(scene);
        if (sc == null) return Result.fail("Unknown scene '" + scene + "'");
        String p = prefix == null ? "" : prefix;
        if (p.length() > 13) return Result.fail("Prefix can be at most 13 characters");
        int i = 1;
        for (Scene.Entry e : sc.entries) e.appearance.name = p.isEmpty() ? null : p + (i++);
        scenes.save(sc);
        for (String r : new HashSet<>(sc.entries.stream().map(e -> e.recording).toList())) refreshRunning(r);
        changed();
        return Result.ok(p.isEmpty() ? "Reset all names" : "Named " + sc.entries.size() + " actors " + p + "1.." + p + sc.entries.size());
    }

    public Result crowdNametags(String scene, Boolean hidden) {
        return editAppearance("*", scene, a -> a.hideNametag = hidden, hidden == null ? "Nametags reset" : hidden ? "Nametags hidden" : "Nametags shown");
    }

    // ------------------------------------------------------------------ playback control

    public Result stop(int id) {
        return playback.stop(id) ? Result.ok("Stopped #" + id) : Result.fail("No playback #" + id);
    }

    public Result stopAll() {
        return Result.ok("Stopped " + playback.stopAll() + " playback(s)");
    }

    public Result stopScene(String scene) {
        int n = playback.stopScene(scene);
        return n > 0 ? Result.ok("Stopped scene '" + scene + "'") : Result.fail("Scene '" + scene + "' is not playing");
    }

    public Result setPaused(int id, boolean paused) {
        Playback p = playback.get(id);
        if (p == null) return Result.fail("No playback #" + id);
        playback.setPaused(p, paused);
        return Result.ok((paused ? "Paused #" : "Resumed #") + id);
    }

    public Result setAllPaused(boolean paused) {
        List<Playback> all = playback.list();
        for (Playback p : all) playback.setPaused(p, paused);
        return Result.ok((paused ? "Paused " : "Resumed ") + all.size() + " playback(s)");
    }
}
