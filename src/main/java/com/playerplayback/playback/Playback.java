package com.playerplayback.playback;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of actors sharing one timeline. The timeline advances with real time (optionally scaled
 * by the server tick rate when {@link #gameClock} is set), never by counting ticks.
 */
public final class Playback {
    public interface BlockSink {
        void apply(String dimension, double x, double y, double z, CompoundTag state, boolean broken);
    }

    public final int id;
    public final String label;
    public final List<PlaybackActor> actors = new ArrayList<>();
    public final boolean loop;
    public final double speed;
    public final boolean gameClock;
    public final boolean replayBlocks;
    /** Set for scene playbacks so the GUI/commands can find them. */
    public final String sceneName;

    public boolean paused;
    double elapsedMs;
    long lastStepNanos = System.nanoTime();
    boolean finished;
    final List<PlaybackManager.BlockChange> blockChanges = new ArrayList<>();

    Playback(int id, String label, String sceneName, boolean loop, double speed, boolean gameClock, boolean replayBlocks) {
        this.id = id;
        this.label = label;
        this.sceneName = sceneName;
        this.loop = loop;
        this.speed = speed;
        this.gameClock = gameClock;
        this.replayBlocks = replayBlocks;
    }

    public int lengthMs() {
        int len = 0;
        for (PlaybackActor a : actors) len = Math.max(len, a.endMs());
        return len;
    }

    /** Playbacks with real fake players run on the server thread. */
    public boolean real() {
        for (PlaybackActor a : actors) if (a.real()) return true;
        return false;
    }

    public int elapsedMs() {
        return (int) elapsedMs;
    }

    /** @param rateFactor current tick rate / 20 (only used for the game clock), 0 when frozen */
    void step(long now, double rateFactor, List<ServerPlayer> candidates, double range, BlockSink sink, Runnable onLoop) {
        double delta = (now - lastStepNanos) / 1_000_000.0;
        lastStepNanos = now;
        if (paused) delta = 0;
        if (gameClock) delta *= rateFactor;
        elapsedMs += delta * speed;

        int length = lengthMs();
        if (elapsedMs > length) {
            if (loop && length > 0) {
                elapsedMs %= length;
                onLoop.run();
                for (PlaybackActor a : actors) a.restart();
            } else {
                // Show the very last frame once, then finish.
                for (PlaybackActor a : actors) a.update(length, candidates, range, replayBlocks ? sink : null);
                finished = true;
                return;
            }
        }
        int t = (int) elapsedMs;
        for (PlaybackActor a : actors) a.update(t, candidates, range, replayBlocks ? sink : null);
    }
}
