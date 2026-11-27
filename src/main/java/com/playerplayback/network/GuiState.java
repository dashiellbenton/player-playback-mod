package com.playerplayback.network;

import java.util.ArrayList;
import java.util.List;

/** Plain data sent to the GUI (serialized with Gson). Shared by client and server. */
public final class GuiState {
    public List<Rec> recordings = new ArrayList<>();
    public List<SceneInfo> scenes = new ArrayList<>();
    public List<Active> recordingNow = new ArrayList<>();
    public List<Play> playing = new ArrayList<>();
    public boolean restoreWorld = true;

    public static final class Rec {
        public String name;
        public String actorName;
        public String skin;
        public Boolean hideNametag;
    }

    public static final class SceneInfo {
        public String name;
        public boolean real;
        public List<Entry> entries = new ArrayList<>();
    }

    public static final class Entry {
        public String id;
        public String recording;
        public double speed;
        public Boolean real;
        public double delaySeconds;
        public String actorName;
        public String skin;
        public Boolean hideNametag;
    }

    public static final class Active {
        public String player;
        public String recording;
        public double seconds;
    }

    public static final class Play {
        public int id;
        public String label;
        public String scene;
        public int actors;
        public boolean paused;
        public boolean loop;
    }
}
