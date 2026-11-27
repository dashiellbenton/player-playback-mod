package com.playerplayback.playback;

import com.playerplayback.actor.Appearance;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** One actor in a playback: either a visual-only {@link Actor} or a real fake player ({@link RealActor}). */
public interface PlaybackActor {
    String recordingName();

    /** Scene entry id, or null for a standalone play. */
    String entryId();

    /** End of this actor on the scene timeline (ms). */
    int endMs();

    void update(int sceneTimeMs, List<ServerPlayer> candidates, double range, Playback.BlockSink blocks);

    void restart();

    void despawnAll();

    void setAppearance(Appearance appearance);

    /** True if this actor must be updated on the server thread. */
    boolean real();
}
