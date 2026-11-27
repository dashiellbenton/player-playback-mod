package com.playerplayback.scene;

import com.playerplayback.actor.Appearance;

import java.util.ArrayList;
import java.util.List;

/** A named group of recordings that play back together. The same recording may appear many times (crowds). */
public final class Scene {
    public String name;
    public List<Entry> entries = new ArrayList<>();

    public static final class Entry {
        /** Unique within the scene, e.g. "take1" or "take1+7". */
        public String id;
        public String recording;
        /** Start delay relative to the scene start, in real-time milliseconds. */
        public int delayMs;
        /** Playback speed of this actor relative to the scene (crowd variation). */
        public double speed = 1.0;
        public double offsetX, offsetY, offsetZ;
        /** Scene-specific overrides on top of the recording's own appearance. */
        public Appearance appearance = new Appearance();

        public Entry() {}

        public Entry(String id, String recording, int delayMs) {
            this.id = id;
            this.recording = recording;
            this.delayMs = delayMs;
        }
    }

    public Entry find(String id) {
        for (Entry e : entries) if (id.equals(e.id)) return e;
        return null;
    }

    /** Next free id for another copy of {@code recording}. */
    public String newId(String recording) {
        if (find(recording) == null) return recording;
        for (int i = 2; ; i++) {
            String id = recording + "+" + i;
            if (find(id) == null) return id;
        }
    }
}
