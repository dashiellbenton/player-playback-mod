package com.playerplayback.scene;

import com.playerplayback.actor.Appearance;

import java.util.ArrayList;
import java.util.List;

/** A named group of recordings that play back together. */
public final class Scene {
    public String name;
    public List<Entry> entries = new ArrayList<>();

    public static final class Entry {
        public String recording;
        /** Start delay relative to the scene start, in real-time milliseconds. */
        public int delayMs;
        public double offsetX, offsetY, offsetZ;
        /** Scene-specific overrides on top of the recording's own appearance. */
        public Appearance appearance = new Appearance();

        public Entry() {}

        public Entry(String recording, int delayMs) {
            this.recording = recording;
            this.delayMs = delayMs;
        }
    }

    public Entry find(String recording) {
        for (Entry e : entries) if (e.recording.equals(recording)) return e;
        return null;
    }
}
