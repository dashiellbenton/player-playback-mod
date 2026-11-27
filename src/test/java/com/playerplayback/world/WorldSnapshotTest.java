package com.playerplayback.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotTest {
    private static WorldSnapshot snapshot(long createdAt, int chunkX, String marker) {
        WorldSnapshot s = new WorldSnapshot();
        s.dimension = "minecraft:overworld";
        s.createdAt = createdAt;
        s.time = 18000;
        s.raining = true;
        s.thundering = false;
        WorldSnapshot.ChunkData c = new WorldSnapshot.ChunkData();
        c.x = chunkX;
        c.z = -3;
        CompoundTag states = new CompoundTag();
        states.put("data", new LongArrayTag(new long[]{1, 2, 3}));
        states.putString("marker", marker);
        c.sections.put(-4, states);
        CompoundTag be = new CompoundTag();
        be.putString("id", "minecraft:chest");
        c.blockEntities.add(be);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:zombie");
        c.entities.add(entity);
        s.chunks.put(WorldSnapshot.key(c.x, c.z), c);
        return s;
    }

    @Test
    void roundTripsThroughFile() throws Exception {
        Path file = Files.createTempDirectory("pp-world").resolve("take.ppworld");
        WorldSnapshot original = snapshot(5, 7, "a");
        original.write(file);
        WorldSnapshot copy = WorldSnapshot.read(file);
        assertEquals(original.dimension, copy.dimension);
        assertEquals(original.time, copy.time);
        assertEquals(original.raining, copy.raining);
        assertEquals(1, copy.chunks.size());
        WorldSnapshot.ChunkData c = copy.chunks.get(WorldSnapshot.key(7, -3));
        assertNotNull(c);
        assertEquals(original.chunks.values().iterator().next().sections, c.sections);
        assertEquals("minecraft:chest", c.blockEntities.get(0).getStringOr("id", ""));
        assertEquals("minecraft:zombie", c.entities.get(0).getStringOr("id", ""));
    }

    @Test
    void mergePrefersTheOldestSnapshot() {
        WorldSnapshot newer = snapshot(200, 7, "newer");
        WorldSnapshot older = snapshot(100, 7, "older");
        WorldSnapshot other = snapshot(300, 8, "other-chunk");
        WorldSnapshot merged = WorldSnapshot.merge(List.of(newer, other, older));
        assertEquals(2, merged.chunks.size());
        CompoundTag states = (CompoundTag) merged.chunks.get(WorldSnapshot.key(7, -3)).sections.get(-4);
        assertEquals("older", states.getStringOr("marker", ""));
        assertEquals(100, merged.createdAt);
    }

    @Test
    void chunkKeysDontCollide() {
        assertNotEquals(WorldSnapshot.key(1, -1), WorldSnapshot.key(-1, 1));
        assertNotEquals(WorldSnapshot.key(0, -1), WorldSnapshot.key(-1, 0));
    }
}
