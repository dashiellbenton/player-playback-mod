package com.playerplayback.recording;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {
    private static Recording sample() {
        Recording r = new Recording();
        r.originalName = "Steve";
        r.originalUuid = UUID.randomUUID();
        r.skinValue = "dGV4dHVyZXM=";
        r.skinSignature = "c2ln";
        r.dimension = "minecraft:overworld";
        r.durationMs = 1500;
        r.createdAt = 123456789L;
        CompoundTag item = new CompoundTag();
        item.putString("probe", "sword");
        r.initialEquipment.put("mainhand", item);
        r.frames.add(new Frame(0, 1.5, 64, -3.25, 90f, 10f, 95f, true, (byte) 0, (byte) 0, (byte) Pose.STANDING.ordinal()));
        r.frames.add(new Frame(50, 1.6, 64.2, -3.2, 91f, 11f, 96f, false, (byte) 0b1010, (byte) 1, (byte) Pose.CROUCHING.ordinal()));
        r.frames.add(new Frame(1500, 2, 64, -3, 92f, 12f, 97f, true, (byte) 0x80, (byte) 0, (byte) Pose.FALL_FLYING.ordinal()));
        r.events.add(new ActorEvent.Swing(10, false, -1, -1));
        r.events.add(new ActorEvent.Swing(20, true, 2, 8));
        r.events.add(new ActorEvent.Animate(30, ActorEvent.ANIM_CRIT));
        r.events.add(new ActorEvent.Hurt(40, 45f));
        r.events.add(new ActorEvent.EntityEvent(50, (byte) 35));
        r.events.add(new ActorEvent.Equip(60, "head", item));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        r.events.add(new ActorEvent.Block(70, 1, 2, 3, state, true));
        r.events.add(new ActorEvent.Sound(80, "minecraft:entity.ender_pearl.throw", "players", 1.5, 65.6, -3.2, 0.5f, 0.4f, 42L));
        r.events.add(new ActorEvent.ProjectileStart(90, 0, "minecraft:ender_pearl", 1.5, 65.6, -3.2, 0.1, 0.5, 1.2, 90f, -20f, item));
        r.events.add(new ActorEvent.ProjectileMove(140, 0, 1.6, 66.1, -2.0, 91f, -18f));
        r.events.add(new ActorEvent.ProjectileEnd(900, 0));
        r.events.add(new ActorEvent.Attack(950, 3.5, 64, -1.25, 0.92f, true));
        return r;
    }

    @Test
    void roundTripsThroughStream() throws Exception {
        Recording original = sample();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        original.write(new DataOutputStream(bytes));
        Recording copy = Recording.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertSame(original, copy);
    }

    @Test
    void roundTripsThroughGzipFile() throws Exception {
        Path dir = Files.createTempDirectory("pp-test");
        Path file = dir.resolve("recordings/take.pprec");
        Recording original = sample();
        original.write(file);
        assertSame(original, Recording.read(file));
        assertFalse(Files.exists(file.resolveSibling("take.pprec.tmp")));
    }

    @Test
    void rejectsGarbage() {
        byte[] junk = {1, 2, 3, 4, 5, 6, 7, 8};
        assertThrows(java.io.IOException.class, () -> Recording.read(new DataInputStream(new ByteArrayInputStream(junk))));
    }

    private static void assertSame(Recording a, Recording b) {
        assertEquals(a.originalName, b.originalName);
        assertEquals(a.originalUuid, b.originalUuid);
        assertEquals(a.skinValue, b.skinValue);
        assertEquals(a.skinSignature, b.skinSignature);
        assertEquals(a.dimension, b.dimension);
        assertEquals(a.durationMs, b.durationMs);
        assertEquals(a.createdAt, b.createdAt);
        assertEquals(a.initialEquipment, b.initialEquipment);
        assertEquals(a.frames, b.frames);
        assertEquals(a.events, b.events);
    }
}
