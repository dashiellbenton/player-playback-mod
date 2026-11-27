package com.playerplayback.scene;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FormationTest {
    @Test
    void everyFormationGivesTheRequestedCountWithOriginFirst() {
        for (Formation f : Formation.values()) {
            for (int count : new int[]{1, 7, 100}) {
                List<Vec3> layout = f.layout(count, 1.5, 37f, new Random(1));
                assertEquals(count, layout.size(), f + " " + count);
                assertTrue(layout.get(0).length() < 1e-9, f + " should start at the original spot");
            }
        }
    }

    @Test
    void gridSlotsAreDistinctAndSpaced() {
        List<Vec3> layout = Formation.GRID.layout(100, 2.0, 0f, new Random(1));
        Set<String> keys = new HashSet<>();
        for (Vec3 v : layout) assertTrue(keys.add(Math.round(v.x * 10) + "," + Math.round(v.z * 10)));
        for (int i = 0; i < layout.size(); i++)
            for (int j = i + 1; j < layout.size(); j++)
                assertTrue(layout.get(i).distanceTo(layout.get(j)) >= 1.99);
    }

    @Test
    void lineIsSideBySideRelativeToFacing() {
        // Facing south (+Z): a line should spread along X only.
        List<Vec3> layout = Formation.LINE.layout(3, 2.0, 0f, new Random(1));
        for (Vec3 v : layout) assertEquals(0.0, v.z, 1e-9);
        // Columns go backwards: facing south means behind is -Z.
        List<Vec3> column = Formation.COLUMN.layout(3, 2.0, 0f, new Random(1));
        assertEquals(-4.0, column.get(2).z, 1e-9);
    }
}
