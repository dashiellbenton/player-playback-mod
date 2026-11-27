package com.playerplayback.recording;

/**
 * One sample of a recorded player's state.
 *
 * @param timeMs real-time milliseconds since the recording started (independent of /tick rate)
 */
public record Frame(
        int timeMs,
        double x, double y, double z,
        float yaw, float pitch, float headYaw,
        boolean onGround,
        byte sharedFlags,
        byte livingFlags,
        byte pose
) {
    public boolean sameStateAs(Frame o) {
        return o != null
                && x == o.x && y == o.y && z == o.z
                && yaw == o.yaw && pitch == o.pitch && headYaw == o.headYaw
                && onGround == o.onGround && sharedFlags == o.sharedFlags
                && livingFlags == o.livingFlags && pose == o.pose;
    }
}
