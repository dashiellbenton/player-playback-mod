package com.playerplayback.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.playerplayback.PlayerPlayback;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-wide mod settings, saved as {@code <world>/playerplayback/settings.json}. */
public final class Settings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Restore the world snapshot before each playback. */
    public boolean restoreWorld = true;
    /** Radius (in chunks) around the recorded player that gets snapshotted when recording starts. */
    public int snapshotRadius = 8;

    private transient Path file;

    public static Settings load(Path file) {
        Settings s = null;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                s = GSON.fromJson(r, Settings.class);
            } catch (Exception e) {
                PlayerPlayback.LOGGER.error("Failed to read {}", file, e);
            }
        }
        if (s == null) s = new Settings();
        s.file = file;
        return s;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            PlayerPlayback.LOGGER.error("Failed to write {}", file, e);
        }
    }
}
