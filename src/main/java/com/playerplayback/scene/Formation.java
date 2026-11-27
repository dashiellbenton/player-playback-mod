package com.playerplayback.scene;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Crowd layouts. Offsets are rotated to the recorded player's starting direction, so "line"
 * means side by side and "grid" means ranks behind the original, wherever the take was facing.
 * The first slot is always the original position (0, 0, 0).
 */
public enum Formation {
    GRID, LINE, COLUMN, WEDGE, CIRCLE, RANDOM;

    public List<Vec3> layout(int count, double spacing, float yawDegrees, Random random) {
        List<double[]> local = new ArrayList<>(); // [right, back]
        switch (this) {
            case GRID -> {
                int cols = (int) Math.ceil(Math.sqrt(count));
                for (int i = 0; i < count; i++) {
                    int row = i / cols, col = i % cols;
                    local.add(new double[]{(col - (cols - 1) / 2.0) * spacing, row * spacing});
                }
            }
            case LINE -> {
                for (int i = 0; i < count; i++) local.add(new double[]{(i - (count - 1) / 2.0) * spacing, 0});
            }
            case COLUMN -> {
                for (int i = 0; i < count; i++) local.add(new double[]{0, i * spacing});
            }
            case WEDGE -> {
                local.add(new double[]{0, 0});
                for (int row = 1; local.size() < count; row++) {
                    for (int k = -row; k <= row && local.size() < count; k += 2) {
                        local.add(new double[]{k * spacing / 2.0, row * spacing});
                    }
                }
            }
            case CIRCLE -> {
                double radius = Math.max(spacing, count * spacing / (2 * Math.PI));
                for (int i = 0; i < count; i++) {
                    double a = 2 * Math.PI * i / count;
                    local.add(new double[]{Math.sin(a) * radius, radius - Math.cos(a) * radius});
                }
            }
            case RANDOM -> {
                double radius = Math.sqrt(count) * spacing / 1.5;
                local.add(new double[]{0, 0});
                while (local.size() < count) {
                    double r = Math.sqrt(random.nextDouble()) * radius, a = random.nextDouble() * 2 * Math.PI;
                    local.add(new double[]{Math.cos(a) * r, Math.sin(a) * r});
                }
            }
        }
        // Shift so the slot nearest the original position sits exactly on it, and list it first.
        int nearest = 0;
        for (int i = 1; i < local.size(); i++) {
            double[] p = local.get(i), n = local.get(nearest);
            if (p[0] * p[0] + p[1] * p[1] < n[0] * n[0] + n[1] * n[1]) nearest = i;
        }
        double[] anchor = local.remove(nearest);
        local.add(0, anchor);
        double ax = anchor[0], ab = anchor[1];
        for (double[] p : local) {
            p[0] -= ax;
            p[1] -= ab;
        }

        // Minecraft yaw: 0 = facing +Z (south). Forward = (-sin, cos); right = (-cos, -sin).
        double yaw = Math.toRadians(yawDegrees);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        List<Vec3> out = new ArrayList<>();
        for (double[] p : local) {
            double right = p[0], back = p[1];
            out.add(new Vec3(right * rx - back * fx, 0, right * rz - back * fz));
        }
        return out;
    }
}
