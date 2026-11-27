package com.playerplayback.recording;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Pose;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A finished recording of one player ("take"). Stored as a small gzip'd binary file.
 */
public final class Recording {
    private static final int MAGIC = 0x50505243; // "PPRC"
    private static final int FORMAT = 2;

    private static final byte EV_SWING = 1, EV_ANIM = 2, EV_HURT = 3, EV_ENTITY = 4, EV_EQUIP = 5, EV_BLOCK = 6,
            EV_SOUND = 7, EV_PROJ_START = 8, EV_PROJ_MOVE = 9, EV_PROJ_END = 10, EV_ATTACK = 11;

    public String originalName;
    public UUID originalUuid;
    public String skinValue;      // may be null
    public String skinSignature;  // may be null
    public String dimension;
    public int durationMs;
    public long createdAt;
    /** Equipment at the start of the recording: slot name -> encoded stack. */
    public final Map<String, CompoundTag> initialEquipment = new LinkedHashMap<>();
    public final List<Frame> frames = new ArrayList<>();
    public final List<ActorEvent> events = new ArrayList<>();

    public Frame firstFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    // ------------------------------------------------------------------ IO

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            write(out);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        out.writeUTF(nn(originalName));
        out.writeLong(originalUuid == null ? 0 : originalUuid.getMostSignificantBits());
        out.writeLong(originalUuid == null ? 0 : originalUuid.getLeastSignificantBits());
        out.writeUTF(nn(skinValue));
        out.writeUTF(nn(skinSignature));
        out.writeUTF(nn(dimension));
        out.writeInt(durationMs);
        out.writeLong(createdAt);

        Pose[] poses = Pose.values();
        out.writeInt(poses.length);
        for (Pose p : poses) out.writeUTF(p.name());

        CompoundTag equip = new CompoundTag();
        initialEquipment.forEach(equip::put);
        NbtIo.write(equip, out);

        out.writeInt(frames.size());
        for (Frame f : frames) {
            out.writeInt(f.timeMs());
            out.writeDouble(f.x());
            out.writeDouble(f.y());
            out.writeDouble(f.z());
            out.writeFloat(f.yaw());
            out.writeFloat(f.pitch());
            out.writeFloat(f.headYaw());
            out.writeBoolean(f.onGround());
            out.writeByte(f.sharedFlags());
            out.writeByte(f.livingFlags());
            out.writeByte(f.pose());
        }

        out.writeInt(events.size());
        for (ActorEvent e : events) {
            out.writeInt(e.timeMs());
            switch (e) {
                case ActorEvent.Swing s -> {
                    out.writeByte(EV_SWING);
                    out.writeBoolean(s.offHand());
                    out.writeInt(s.animType());
                    out.writeInt(s.duration());
                }
                case ActorEvent.Animate a -> {
                    out.writeByte(EV_ANIM);
                    out.writeInt(a.kind());
                }
                case ActorEvent.Hurt h -> {
                    out.writeByte(EV_HURT);
                    out.writeFloat(h.yaw());
                }
                case ActorEvent.EntityEvent ee -> {
                    out.writeByte(EV_ENTITY);
                    out.writeByte(ee.id());
                }
                case ActorEvent.Equip eq -> {
                    out.writeByte(EV_EQUIP);
                    out.writeUTF(eq.slot());
                    NbtIo.write(eq.item(), out);
                }
                case ActorEvent.Block b -> {
                    out.writeByte(EV_BLOCK);
                    out.writeInt(b.x());
                    out.writeInt(b.y());
                    out.writeInt(b.z());
                    out.writeBoolean(b.broken());
                    NbtIo.write(b.state(), out);
                }
                case ActorEvent.Sound so -> {
                    out.writeByte(EV_SOUND);
                    out.writeUTF(so.sound());
                    out.writeUTF(so.source());
                    out.writeDouble(so.x());
                    out.writeDouble(so.y());
                    out.writeDouble(so.z());
                    out.writeFloat(so.volume());
                    out.writeFloat(so.pitch());
                    out.writeLong(so.seed());
                }
                case ActorEvent.ProjectileStart ps -> {
                    out.writeByte(EV_PROJ_START);
                    out.writeInt(ps.id());
                    out.writeUTF(ps.type());
                    out.writeDouble(ps.x());
                    out.writeDouble(ps.y());
                    out.writeDouble(ps.z());
                    out.writeDouble(ps.vx());
                    out.writeDouble(ps.vy());
                    out.writeDouble(ps.vz());
                    out.writeFloat(ps.yaw());
                    out.writeFloat(ps.pitch());
                    NbtIo.write(ps.item(), out);
                }
                case ActorEvent.ProjectileMove pm -> {
                    out.writeByte(EV_PROJ_MOVE);
                    out.writeInt(pm.id());
                    out.writeDouble(pm.x());
                    out.writeDouble(pm.y());
                    out.writeDouble(pm.z());
                    out.writeFloat(pm.yaw());
                    out.writeFloat(pm.pitch());
                }
                case ActorEvent.ProjectileEnd pe -> {
                    out.writeByte(EV_PROJ_END);
                    out.writeInt(pe.id());
                }
                case ActorEvent.Attack at -> {
                    out.writeByte(EV_ATTACK);
                    out.writeDouble(at.x());
                    out.writeDouble(at.y());
                    out.writeDouble(at.z());
                    out.writeFloat(at.strength());
                    out.writeBoolean(at.crit());
                }
            }
        }
    }

    public static Recording read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            return read(in);
        }
    }

    public static Recording read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Not a Player Playback recording");
        int format = in.readInt();
        if (format > FORMAT) throw new IOException("Recording was made with a newer version of the mod (format " + format + ")");
        Recording r = new Recording();
        r.originalName = in.readUTF();
        long msb = in.readLong(), lsb = in.readLong();
        r.originalUuid = (msb == 0 && lsb == 0) ? null : new UUID(msb, lsb);
        r.skinValue = emptyToNull(in.readUTF());
        r.skinSignature = emptyToNull(in.readUTF());
        r.dimension = in.readUTF();
        r.durationMs = in.readInt();
        r.createdAt = in.readLong();

        // Map pose ordinals stored in the file to this game version's ordinals by name.
        int poseCount = in.readInt();
        byte[] poseMap = new byte[poseCount];
        for (int i = 0; i < poseCount; i++) {
            String name = in.readUTF();
            byte mapped = 0;
            for (Pose p : Pose.values()) if (p.name().equals(name)) mapped = (byte) p.ordinal();
            poseMap[i] = mapped;
        }

        CompoundTag equip = NbtIo.read(in, NbtAccounter.unlimitedHeap());
        for (String key : equip.keySet()) {
            equip.getCompound(key).ifPresent(tag -> r.initialEquipment.put(key, tag));
        }

        int frameCount = in.readInt();
        for (int i = 0; i < frameCount; i++) {
            int t = in.readInt();
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat(), head = in.readFloat();
            boolean ground = in.readBoolean();
            byte flags = in.readByte(), living = in.readByte(), pose = in.readByte();
            int pi = pose & 0xFF;
            r.frames.add(new Frame(t, x, y, z, yaw, pitch, head, ground, flags, living, pi < poseMap.length ? poseMap[pi] : 0));
        }

        int eventCount = in.readInt();
        for (int i = 0; i < eventCount; i++) {
            int t = in.readInt();
            byte type = in.readByte();
            switch (type) {
                case EV_SWING -> r.events.add(new ActorEvent.Swing(t, in.readBoolean(), in.readInt(), in.readInt()));
                case EV_ANIM -> r.events.add(new ActorEvent.Animate(t, in.readInt()));
                case EV_HURT -> r.events.add(new ActorEvent.Hurt(t, in.readFloat()));
                case EV_ENTITY -> r.events.add(new ActorEvent.EntityEvent(t, in.readByte()));
                case EV_EQUIP -> r.events.add(new ActorEvent.Equip(t, in.readUTF(), NbtIo.read(in, NbtAccounter.unlimitedHeap())));
                case EV_BLOCK -> {
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    boolean broken = in.readBoolean();
                    r.events.add(new ActorEvent.Block(t, x, y, z, NbtIo.read(in, NbtAccounter.unlimitedHeap()), broken));
                }
                case EV_SOUND -> r.events.add(new ActorEvent.Sound(t, in.readUTF(), in.readUTF(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readFloat(), in.readFloat(), in.readLong()));
                case EV_PROJ_START -> {
                    int id = in.readInt();
                    String pt = in.readUTF();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    double vx = in.readDouble(), vy = in.readDouble(), vz = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat();
                    r.events.add(new ActorEvent.ProjectileStart(t, id, pt, x, y, z, vx, vy, vz, yaw, pitch, NbtIo.read(in, NbtAccounter.unlimitedHeap())));
                }
                case EV_PROJ_MOVE -> r.events.add(new ActorEvent.ProjectileMove(t, in.readInt(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readFloat(), in.readFloat()));
                case EV_PROJ_END -> r.events.add(new ActorEvent.ProjectileEnd(t, in.readInt()));
                case EV_ATTACK -> r.events.add(new ActorEvent.Attack(t, in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readFloat(), in.readBoolean()));
                default -> throw new IOException("Unknown event type " + type);
            }
        }
        return r;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
