package com.playerplayback.playback;

import com.playerplayback.PlayerPlayback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs all playbacks on a dedicated 20 Hz real-time thread. Because it does not depend on the
 * server tick loop, actors keep moving at the recorded speed whatever {@code /tick rate} is set to
 * (and even under {@code /tick freeze}). World changes (block replay) are handed to the server thread.
 */
public final class PlaybackManager {
    public static final Object LOCK = new Object();
    private static final long STEP_MS = 50;

    record BlockChange(ResourceKey<Level> dimension, BlockPos pos, BlockState original) {}

    private final MinecraftServer server;
    private final Map<Integer, Playback> playbacks = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> task;
    private int nextId = 1;
    private volatile List<ServerPlayer> players = List.of();
    private volatile double rateFactor = 1.0;
    private volatile double rangeBlocks = 160;
    /** Called (on the server thread) whenever the set of playbacks changes. */
    private Runnable changeListener = () -> {};

    public PlaybackManager(MinecraftServer server) {
        this.server = server;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Player Playback");
            t.setDaemon(true);
            return t;
        });
        this.task = executor.scheduleAtFixedRate(this::stepSafely, STEP_MS, STEP_MS, TimeUnit.MILLISECONDS);
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Server thread, every tick: publish a snapshot of state the playback thread needs. */
    public void onServerTick() {
        players = List.copyOf(server.getPlayerList().getPlayers());
        var rate = server.tickRateManager();
        rateFactor = rate.isFrozen() ? 0.0 : rate.tickrate() / 20.0;
        rangeBlocks = Math.max(2, Math.min(server.getPlayerList().getViewDistance(), 32)) * 16.0;
        stepPlaybacks(true);
    }

    /** A real player joined: tell their client about the fake players that already exist. */
    public void onPlayerJoin(ServerPlayer player) {
        synchronized (LOCK) {
            for (Playback p : playbacks.values()) {
                for (PlaybackActor a : p.actors) if (a instanceof RealActor r) r.introduceTo(player);
            }
        }
    }

    public Playback create(String label, String sceneName, boolean loop, double speed, boolean gameClock, boolean replayBlocks) {
        synchronized (LOCK) {
            return new Playback(nextId++, label, sceneName, loop, speed, gameClock, replayBlocks);
        }
    }

    public void start(Playback playback) {
        synchronized (LOCK) {
            playback.lastStepNanos = System.nanoTime();
            playbacks.put(playback.id, playback);
        }
        notifyChange();
    }

    public boolean stop(int id) {
        Playback p;
        synchronized (LOCK) {
            p = playbacks.remove(id);
            if (p != null) end(p);
        }
        if (p != null) notifyChange();
        return p != null;
    }

    public int stopAll() {
        int n;
        synchronized (LOCK) {
            n = playbacks.size();
            for (Playback p : playbacks.values()) end(p);
            playbacks.clear();
        }
        notifyChange();
        return n;
    }

    public int stopScene(String scene) {
        List<Integer> ids = new ArrayList<>();
        synchronized (LOCK) {
            for (Playback p : playbacks.values()) if (scene.equals(p.sceneName)) ids.add(p.id);
        }
        ids.forEach(this::stop);
        return ids.size();
    }

    public List<Playback> list() {
        synchronized (LOCK) {
            return new ArrayList<>(playbacks.values());
        }
    }

    public Playback get(int id) {
        synchronized (LOCK) {
            return playbacks.get(id);
        }
    }

    public void setPaused(Playback p, boolean paused) {
        synchronized (LOCK) {
            p.paused = paused;
        }
        notifyChange();
    }

    /** Re-applies appearance to running actors of a recording (e.g. after a skin change). */
    public void refreshActors(String recording, java.util.function.Function<PlaybackActor, com.playerplayback.actor.Appearance> appearance) {
        synchronized (LOCK) {
            for (Playback p : playbacks.values()) {
                for (PlaybackActor a : p.actors) if (a.recordingName().equals(recording)) a.setAppearance(appearance.apply(a));
            }
        }
    }

    public void shutdown() {
        task.cancel(false);
        executor.shutdown();
        synchronized (LOCK) {
            for (Playback p : playbacks.values()) end(p);
            playbacks.clear();
        }
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void end(Playback p) {
        for (PlaybackActor a : p.actors) a.despawnAll();
        revertBlocks(p);
    }

    private void stepSafely() {
        try {
            step();
        } catch (Throwable t) {
            PlayerPlayback.LOGGER.error("Error while running playback", t);
        }
    }

    private void step() {
        stepPlaybacks(false);
    }

    /**
     * Steps playbacks. Visual-only playbacks run on the dedicated real-time thread; playbacks with
     * real fake players must touch the world, so they run on the server thread (onServerThread).
     * Both use the real-time clock for their timeline.
     */
    private void stepPlaybacks(boolean onServerThread) {
        boolean changed = false;
        synchronized (LOCK) {
            if (playbacks.isEmpty()) return;
            long now = System.nanoTime();
            List<ServerPlayer> snapshot = players;
            double range = rangeBlocks;
            double rate = rateFactor;
            Iterator<Playback> it = playbacks.values().iterator();
            while (it.hasNext()) {
                Playback p = it.next();
                if (p.real() != onServerThread) continue;
                p.step(now, rate, snapshot, range, (dim, x, y, z, state, broken) -> queueBlock(p, dim, x, y, z, state, broken),
                        () -> revertBlocks(p));
                if (p.finished) {
                    end(p);
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed) notifyChange();
    }

    private void notifyChange() {
        server.execute(changeListener);
    }

    // ---------------------------------------------------------------- block replay

    private void queueBlock(Playback p, String dimension, double x, double y, double z, CompoundTag stateTag, boolean broken) {
        server.execute(() -> {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
            ServerLevel level = server.getLevel(key);
            if (level == null) return;
            BlockPos pos = BlockPos.containing(x, y, z);
            BlockState state = NbtUtils.readBlockState(server.registryAccess().lookupOrThrow(Registries.BLOCK), stateTag);
            BlockState old = level.getBlockState(pos);
            synchronized (LOCK) {
                boolean known = false;
                for (BlockChange c : p.blockChanges) if (c.pos().equals(pos) && c.dimension().equals(key)) known = true;
                if (!known) p.blockChanges.add(new BlockChange(key, pos, old));
            }
            if (broken && !old.isAir()) level.levelEvent(2001, pos, Block.getId(old));
            level.setBlock(pos, state, Block.UPDATE_ALL);
        });
    }

    private void revertBlocks(Playback p) {
        List<BlockChange> changes = new ArrayList<>(p.blockChanges);
        p.blockChanges.clear();
        if (changes.isEmpty()) return;
        server.execute(() -> {
            for (int i = changes.size() - 1; i >= 0; i--) {
                BlockChange c = changes.get(i);
                ServerLevel level = server.getLevel(c.dimension());
                if (level != null) level.setBlock(c.pos(), c.original(), Block.UPDATE_ALL);
            }
        });
    }
}
