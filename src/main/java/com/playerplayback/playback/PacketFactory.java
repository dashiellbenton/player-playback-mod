package com.playerplayback.playback;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import com.playerplayback.mixin.AvatarAccessor;
import com.playerplayback.mixin.EntityAccessor;
import com.playerplayback.mixin.LivingEntityAccessor;
import com.playerplayback.mixin.PlayerInfoUpdatePacketAccessor;
import com.playerplayback.recording.Frame;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.*;

/**
 * Builds every packet an actor needs. Packets whose public constructors require a real entity
 * are created by decoding a hand-written buffer instead, which works the same on every version.
 */
public final class PacketFactory {
    //? if >=26.2 {
    private static final EntityType<?> PLAYER_TYPE = net.minecraft.world.entity.EntityTypes.PLAYER;
    //?} else {
    /*private static final EntityType<?> PLAYER_TYPE = EntityType.PLAYER;
    *///?}
    private static final Pose[] POSES = Pose.values();
    private static final Scoreboard DUMMY_SCOREBOARD = new Scoreboard();

    private PacketFactory() {}

    public static ClientboundPlayerInfoUpdatePacket addPlayerInfo(GameProfile profile) {
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of());
        ((PlayerInfoUpdatePacketAccessor) packet).pp$setEntries(List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
                profile.id(), profile, false, 0, GameType.SURVIVAL, null, true, 0, null)));
        return packet;
    }

    public static ClientboundPlayerInfoRemovePacket removePlayerInfo(UUID id) {
        return new ClientboundPlayerInfoRemovePacket(List.of(id));
    }

    public static ClientboundAddEntityPacket addEntity(int id, UUID uuid, Frame f, Vec3 offset) {
        return new ClientboundAddEntityPacket(id, uuid, f.x() + offset.x, f.y() + offset.y, f.z() + offset.z,
                f.pitch(), f.yaw(), PLAYER_TYPE, 0, Vec3.ZERO, f.headYaw());
    }

    public static ClientboundRemoveEntitiesPacket removeEntity(int id) {
        return new ClientboundRemoveEntitiesPacket(id);
    }

    public static ClientboundTeleportEntityPacket teleport(int id, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        return ClientboundTeleportEntityPacket.teleport(id, new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch),
                Set.of(), onGround);
    }

    public static Packet<?> rotateHead(int id, float headYaw) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(id);
        buf.writeByte(Mth.packDegrees(headYaw));
        return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf);
    }

    public static Packet<?> animate(int id, int action) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(id);
        buf.writeByte(action);
        return ClientboundAnimatePacket.STREAM_CODEC.decode(buf);
    }

    public static Packet<?> entityEvent(int id, byte event) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeInt(id);
        buf.writeByte(event);
        return ClientboundEntityEventPacket.STREAM_CODEC.decode(buf);
    }

    public static Packet<?> hurt(int id, float yaw) {
        return new ClientboundHurtAnimationPacket(id, yaw);
    }

    /** Full entity data, sent on spawn. */
    public static ClientboundSetEntityDataPacket fullData(int id, Frame f) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(stateData(f));
        values.add(SynchedEntityData.DataValue.create(AvatarAccessor.pp$customisation(), (byte) 0x7F));
        return new ClientboundSetEntityDataPacket(id, values);
    }

    /** Only the fields that change frame-to-frame. */
    public static ClientboundSetEntityDataPacket stateDataPacket(int id, Frame f) {
        return new ClientboundSetEntityDataPacket(id, stateData(f));
    }

    private static List<SynchedEntityData.DataValue<?>> stateData(Frame f) {
        int poseIndex = f.pose() & 0xFF;
        Pose pose = poseIndex < POSES.length ? POSES[poseIndex] : Pose.STANDING;
        return List.of(
                SynchedEntityData.DataValue.create(EntityAccessor.pp$sharedFlags(), f.sharedFlags()),
                SynchedEntityData.DataValue.create(EntityAccessor.pp$pose(), pose),
                SynchedEntityData.DataValue.create(LivingEntityAccessor.pp$livingFlags(), f.livingFlags())
        );
    }

    public static ClientboundSetEquipmentPacket equipment(int id, Map<EquipmentSlot, ItemStack> items) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        items.forEach((slot, stack) -> list.add(Pair.of(slot, stack)));
        return new ClientboundSetEquipmentPacket(id, list);
    }

    public static PlayerTeam team(String teamName, String member, boolean hideNametag, Component prefix) {
        PlayerTeam team = new PlayerTeam(DUMMY_SCOREBOARD, teamName);
        team.setNameTagVisibility(hideNametag ? Team.Visibility.NEVER : Team.Visibility.ALWAYS);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        if (prefix != null) team.setPlayerPrefix(prefix);
        team.getPlayers().add(member);
        return team;
    }

    public static ClientboundSetPlayerTeamPacket addTeam(PlayerTeam team) {
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
    }

    public static ClientboundSetPlayerTeamPacket removeTeam(PlayerTeam team) {
        return ClientboundSetPlayerTeamPacket.createRemovePacket(team);
    }
}
