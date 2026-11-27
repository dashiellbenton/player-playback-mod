package com.playerplayback.playback;

import com.playerplayback.recording.Frame;
import com.playerplayback.recording.Recording;
import net.minecraft.util.Mth;

import java.util.List;

/** Walks a recording's frames forward in time, interpolating between samples. */
final class FrameCursor {
    private final List<Frame> frames;
    private int index;

    FrameCursor(Recording recording) {
        this.frames = recording.frames;
    }

    void reset() {
        index = 0;
    }

    /** State at recording time {@code t} (ms). Call with non-decreasing t, or {@link #reset()} first. */
    Frame sample(int t) {
        while (index + 1 < frames.size() && frames.get(index + 1).timeMs() <= t) index++;
        Frame a = frames.get(index);
        if (index + 1 >= frames.size() || t <= a.timeMs()) return a;
        Frame b = frames.get(index + 1);
        int span = b.timeMs() - a.timeMs();
        if (span <= 0 || distSq(a, b) > 8 * 8) return a; // don't smear teleports (pearls, respawns)
        float k = (t - a.timeMs()) / (float) span;
        return new Frame(t,
                Mth.lerp(k, a.x(), b.x()), Mth.lerp(k, a.y(), b.y()), Mth.lerp(k, a.z(), b.z()),
                Mth.rotLerp(k, a.yaw(), b.yaw()), Mth.lerp(k, a.pitch(), b.pitch()), Mth.rotLerp(k, a.headYaw(), b.headYaw()),
                a.onGround(), a.sharedFlags(), a.livingFlags(), a.pose());
    }

    static double distSq(Frame a, Frame b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
