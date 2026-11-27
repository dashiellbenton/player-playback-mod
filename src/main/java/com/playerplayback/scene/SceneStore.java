package com.playerplayback.scene;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playerplayback.PlayerPlayback;
import com.playerplayback.actor.Appearance;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Scenes, one JSON file each in {@code <world>/playerplayback/scenes/}. */
public final class SceneStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final Map<String, Scene> scenes = new TreeMap<>();

    public SceneStore(Path dir) {
        this.dir = dir;
        load();
    }

    public Collection<Scene> all() {
        return scenes.values();
    }

    public Scene get(String name) {
        return scenes.get(name);
    }

    public Scene create(String name) {
        Scene s = new Scene();
        s.name = name;
        scenes.put(name, s);
        save(s);
        return s;
    }

    public void delete(String name) {
        scenes.remove(name);
        try {
            Files.deleteIfExists(dir.resolve(name + ".json"));
        } catch (IOException e) {
            PlayerPlayback.LOGGER.error("Failed to delete scene {}", name, e);
        }
    }

    /** Keeps scene entries consistent when a recording is renamed or deleted. */
    public void recordingRenamed(String from, String to) {
        for (Scene s : scenes.values()) {
            boolean changed = false;
            for (Scene.Entry e : s.entries) {
                if (e.recording.equals(from)) {
                    e.recording = to;
                    if (e.id.equals(from)) e.id = s.newId(to);
                    else if (e.id.startsWith(from + "+")) e.id = to + e.id.substring(from.length());
                    changed = true;
                }
            }
            if (changed) save(s);
        }
    }

    public void recordingDeleted(String name) {
        for (Scene s : scenes.values()) {
            if (s.entries.removeIf(e -> e.recording.equals(name))) save(s);
        }
    }

    private void load() {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Scene s = GSON.fromJson(r, Scene.class);
                    if (s == null) continue;
                    String fileName = file.getFileName().toString();
                    s.name = fileName.substring(0, fileName.length() - ".json".length());
                    if (s.entries == null) s.entries = new java.util.ArrayList<>();
                    for (Scene.Entry e : s.entries) {
                        if (e.appearance == null) e.appearance = new Appearance();
                        if (e.speed <= 0) e.speed = 1.0;
                    }
                    for (Scene.Entry e : s.entries) if (e.id == null) e.id = s.newId(e.recording);
                    scenes.put(s.name, s);
                } catch (Exception e) {
                    PlayerPlayback.LOGGER.error("Failed to read scene {}", file, e);
                }
            }
        } catch (IOException e) {
            PlayerPlayback.LOGGER.error("Failed to list scenes in {}", dir, e);
        }
    }

    public void save(Scene s) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve(s.name + ".json"))) {
                GSON.toJson(s, w);
            }
        } catch (IOException e) {
            PlayerPlayback.LOGGER.error("Failed to write scene {}", s.name, e);
        }
    }
}
