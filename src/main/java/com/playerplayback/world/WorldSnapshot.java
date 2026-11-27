package com.playerplayback.world;

import com.mojang.serialization.Codec;
import com.playerplayback.util.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * The state of the world around a recorded player when recording started: blocks, block
 * entities (chest contents, signs...), mobs and other entities in every loaded chunk nearby, plus
 * time of day and weather. Restoring it before a playback makes every playback start from the
 * same world.
 */
public final class WorldSnapshot {
    /** Restore without neighbor updates, drops, container spills or on-place logic: just put blocks back. */
    private static final int RESTORE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;
    private static final int FORMAT = 1;

    public String dimension;
    public long createdAt;
    public long time;
    public boolean raining;
    public boolean thundering;
    /** Keyed by {@link #key}. */
    public final Map<Long, ChunkData> chunks = new LinkedHashMap<>();

    public static final class ChunkData {
        public int x, z;
        /** Section Y -> encoded block-state container. */
        public final Map<Integer, Tag> sections = new LinkedHashMap<>();
        public final List<CompoundTag> blockEntities = new ArrayList<>();
        public final List<CompoundTag> entities = new ArrayList<>();
    }

    public record RestoreResult(int chunks, int blocks, int blockEntities, int entitiesRemoved, int entitiesSpawned) {}

    // ------------------------------------------------------------------ capture

    /** Captures every loaded chunk within {@code radius} chunks of {@code center}. Server thread. */
    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static WorldSnapshot capture(ServerLevel level, int centerX, int centerZ, int radius) {
        WorldSnapshot s = new WorldSnapshot();
        s.dimension = level.dimension().identifier().toString();
        s.createdAt = System.currentTimeMillis();
        s.time = Compat.getTime(level);
        s.raining = level.isRaining();
        s.thundering = level.isThundering();
        Codec<PalettedContainer<BlockState>> codec = level.palettedContainerFactory().blockStatesContainerCodec();
        var registries = level.registryAccess();

        for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
            for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                ChunkData data = new ChunkData();
                data.x = cx;
                data.z = cz;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    int sectionY = chunk.getSectionYFromSectionIndex(i);
                    codec.encodeStart(NbtOps.INSTANCE, sections[i].getStates()).result()
                            .ifPresent(tag -> data.sections.put(sectionY, tag));
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    data.blockEntities.add(be.saveWithFullMetadata(registries));
                }
                for (Entity e : level.getEntities((Entity) null, chunkBox(level, cx, cz), WorldSnapshot::isRestorable)) {
                    if (e.getVehicle() != null) continue; // saved together with its vehicle
                    TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
                    if (e.saveAsPassenger(out)) data.entities.add(out.buildResult());
                }
                s.chunks.put(key(cx, cz), data);
            }
        }
        return s;
    }

    /** Players (and real-mode actors, which are players) are never captured or removed. */
    private static boolean isRestorable(Entity e) {
        return !(e instanceof Player) && !e.isRemoved();
    }

    private static AABB chunkBox(ServerLevel level, int cx, int cz) {
        return new AABB(cx * 16, level.getMinY(), cz * 16, cx * 16 + 16, level.getMaxY() + 1, cz * 16 + 16);
    }

    // ------------------------------------------------------------------ restore

    /** Puts the world back the way it was. Server thread. */
    public RestoreResult restore(ServerLevel level) {
        Codec<PalettedContainer<BlockState>> codec = level.palettedContainerFactory().blockStatesContainerCodec();
        var registries = level.registryAccess();
        int blocks = 0, blockEntities = 0, removed = 0, spawned = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Clear out current entities first, so nothing is crushed/dropped by the block changes.
        for (ChunkData data : chunks.values()) {
            level.getChunk(data.x, data.z); // make sure it is loaded
            for (Entity e : level.getEntities((Entity) null, chunkBox(level, data.x, data.z), WorldSnapshot::isRestorable)) {
                e.discard();
                removed++;
            }
        }

        // 2. Blocks: only touch positions that actually changed.
        for (ChunkData data : chunks.values()) {
            LevelChunk chunk = level.getChunk(data.x, data.z);
            for (var entry : data.sections.entrySet()) {
                int sectionY = entry.getKey();
                int index = chunk.getSectionIndexFromSectionY(sectionY);
                if (index < 0 || index >= chunk.getSections().length) continue;
                PalettedContainer<BlockState> target = codec.parse(NbtOps.INSTANCE, entry.getValue()).result().orElse(null);
                if (target == null) continue;
                LevelChunkSection current = chunk.getSections()[index];
                int baseX = data.x * 16, baseY = sectionY * 16, baseZ = data.z * 16;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState want = target.get(x, y, z);
                            if (current.getBlockState(x, y, z) != want) {
                                pos.set(baseX + x, baseY + y, baseZ + z);
                                level.setBlock(pos, want, RESTORE_FLAGS);
                                blocks++;
                            }
                        }
                    }
                }
            }
        }

        // 3. Block entities: chest contents, signs, banners... always reloaded, even if the block didn't change.
        for (ChunkData data : chunks.values()) {
            for (CompoundTag tag : data.blockEntities) {
                BlockPos bePos = BlockEntity.getPosFromTag(new ChunkPos(data.x, data.z), tag);
                BlockState state = level.getBlockState(bePos);
                level.removeBlockEntity(bePos);
                BlockEntity be = BlockEntity.loadStatic(bePos, state, tag, registries);
                if (be != null) {
                    level.setBlockEntity(be);
                    level.sendBlockUpdated(bePos, state, state, Block.UPDATE_CLIENTS);
                    blockEntities++;
                }
            }
        }

        // 4. Entities as they were.
        for (ChunkData data : chunks.values()) {
            for (CompoundTag tag : data.entities) {
                Entity e = Compat.loadEntity(tag, level);
                if (e == null) continue;
                if (!level.tryAddFreshEntityWithPassengers(e)) {
                    // An entity with that UUID still exists somewhere: spawn a copy instead.
                    e.getSelfAndPassengers().forEach(p -> p.setUUID(UUID.randomUUID()));
                    if (!level.tryAddFreshEntityWithPassengers(e)) continue;
                }
                spawned++;
            }
        }

        // 5. Time and weather.
        Compat.setTime(level, time);
        Compat.setWeather(level, raining, thundering);
        return new RestoreResult(chunks.size(), blocks, blockEntities, removed, spawned);
    }

    /**
     * Combines snapshots (e.g. from several takes of one scene). For chunks in more than one,
     * the oldest snapshot wins, since it shows the world before any take changed it.
     */
    public static WorldSnapshot merge(List<WorldSnapshot> snapshots) {
        List<WorldSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparingLong(s -> s.createdAt));
        WorldSnapshot first = sorted.get(0);
        WorldSnapshot merged = new WorldSnapshot();
        merged.dimension = first.dimension;
        merged.createdAt = first.createdAt;
        merged.time = first.time;
        merged.raining = first.raining;
        merged.thundering = first.thundering;
        for (WorldSnapshot s : sorted) {
            if (!s.dimension.equals(first.dimension)) continue;
            s.chunks.forEach(merged.chunks::putIfAbsent);
        }
        return merged;
    }

    // ------------------------------------------------------------------ IO

    public CompoundTag toTag() {
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        root.putString("dimension", dimension);
        root.putLong("createdAt", createdAt);
        root.putLong("time", time);
        root.putBoolean("raining", raining);
        root.putBoolean("thundering", thundering);
        ListTag list = new ListTag();
        for (ChunkData c : chunks.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putInt("x", c.x);
            ct.putInt("z", c.z);
            ListTag sections = new ListTag();
            c.sections.forEach((y, states) -> {
                CompoundTag st = new CompoundTag();
                st.putInt("y", y);
                st.put("states", states);
                sections.add(st);
            });
            ct.put("sections", sections);
            ListTag bes = new ListTag();
            bes.addAll(c.blockEntities);
            ct.put("blockEntities", bes);
            ListTag ents = new ListTag();
            ents.addAll(c.entities);
            ct.put("entities", ents);
            list.add(ct);
        }
        root.put("chunks", list);
        return root;
    }

    public static WorldSnapshot fromTag(CompoundTag root) {
        WorldSnapshot s = new WorldSnapshot();
        s.dimension = root.getStringOr("dimension", "minecraft:overworld");
        s.createdAt = root.getLongOr("createdAt", 0);
        s.time = root.getLongOr("time", 0);
        s.raining = root.getBooleanOr("raining", false);
        s.thundering = root.getBooleanOr("thundering", false);
        for (Tag t : root.getListOrEmpty("chunks")) {
            if (!(t instanceof CompoundTag ct)) continue;
            ChunkData c = new ChunkData();
            c.x = ct.getIntOr("x", 0);
            c.z = ct.getIntOr("z", 0);
            for (Tag st : ct.getListOrEmpty("sections")) {
                if (st instanceof CompoundTag sec && sec.get("states") != null) c.sections.put(sec.getIntOr("y", 0), sec.get("states"));
            }
            for (Tag be : ct.getListOrEmpty("blockEntities")) if (be instanceof CompoundTag b) c.blockEntities.add(b);
            for (Tag en : ct.getListOrEmpty("entities")) if (en instanceof CompoundTag e) c.entities.add(e);
            s.chunks.put(key(c.x, c.z), c);
        }
        return s;
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(toTag(), tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static WorldSnapshot read(Path file) throws IOException {
        return fromTag(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }

}
