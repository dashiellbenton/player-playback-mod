package com.playerplayback.actor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.playerplayback.PlayerPlayback;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Per-recording default appearances, saved as {@code appearances.json}. */
public final class AppearanceStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, Appearance> byRecording = new TreeMap<>();

    public AppearanceStore(Path file) {
        this.file = file;
        load();
    }

    public Appearance get(String recording) {
        return byRecording.get(recording);
    }

    public Appearance getOrCreate(String recording) {
        return byRecording.computeIfAbsent(recording, k -> new Appearance());
    }

    public void remove(String recording) {
        if (byRecording.remove(recording) != null) save();
    }

    public void rename(String from, String to) {
        Appearance a = byRecording.remove(from);
        if (a != null) {
            byRecording.put(to, a);
            save();
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Appearance> map = GSON.fromJson(r, new TypeToken<Map<String, Appearance>>() {}.getType());
            if (map != null) byRecording.putAll(map);
        } catch (Exception e) {
            PlayerPlayback.LOGGER.error("Failed to read {}", file, e);
        }
    }

    public void save() {
        byRecording.values().removeIf(Appearance::isEmpty);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(byRecording, w);
            }
        } catch (IOException e) {
            PlayerPlayback.LOGGER.error("Failed to write {}", file, e);
        }
    }
}
