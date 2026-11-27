package com.playerplayback.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.playerplayback.playback.PacketFactory;
import com.playerplayback.recording.ActorEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
//? if >=26.3 {
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
//?}

import java.util.Set;

/**
 * Everything that differs between supported Minecraft versions lives here (Stonecutter
 * version comments switch the code at build time).
 */
public final class Compat {
    private Compat() {}

    /** Entity events worth replaying (totem, shield, item break, death, ...). */
    private static final Set<Byte> REPLAYED_ENTITY_EVENTS = Set.of(
            (byte) 3, (byte) 29, (byte) 30, (byte) 35, (byte) 46,
            (byte) 47, (byte) 48, (byte) 49, (byte) 50, (byte) 51, (byte) 52, (byte) 60);

    public static final byte EVENT_DEATH = 3;

    public static String profileName(GameProfile profile) {
        return profile.name();
    }

    public static Property texturesOf(GameProfile profile) {
        for (Property p : profile.properties().get("textures")) return p;
        return null;
    }

    public static String dimensionId(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    /**
     * Converts a packet the server is broadcasting about a recorded player into a recording event.
     * Returns null for packets that aren't interesting.
     */
    public static ActorEvent eventFromPacket(Packet<?> packet, int t) {
        if (packet instanceof ClientboundAnimatePacket anim) {
            int a = anim.getAction();
            //? if >=26.3 {
            return switch (a) {
                case ClientboundAnimatePacket.WAKE_UP -> new ActorEvent.Animate(t, ActorEvent.ANIM_WAKE_UP);
                case ClientboundAnimatePacket.CRITICAL_HIT -> new ActorEvent.Animate(t, ActorEvent.ANIM_CRIT);
                case ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> new ActorEvent.Animate(t, ActorEvent.ANIM_MAGIC_CRIT);
                default -> null;
            };
            //?} else {
            /*return switch (a) {
                case ClientboundAnimatePacket.SWING_MAIN_HAND -> new ActorEvent.Swing(t, false, -1, -1);
                case ClientboundAnimatePacket.SWING_OFF_HAND -> new ActorEvent.Swing(t, true, -1, -1);
                case ClientboundAnimatePacket.WAKE_UP -> new ActorEvent.Animate(t, ActorEvent.ANIM_WAKE_UP);
                case ClientboundAnimatePacket.CRITICAL_HIT -> new ActorEvent.Animate(t, ActorEvent.ANIM_CRIT);
                case ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> new ActorEvent.Animate(t, ActorEvent.ANIM_MAGIC_CRIT);
                default -> null;
            };
            *///?}
        }
        //? if >=26.3 {
        if (packet instanceof ClientboundSwingAnimationPacket swing) {
            return new ActorEvent.Swing(t, swing.hand() == InteractionHand.OFF_HAND,
                    swing.animation().type().ordinal(), swing.animation().duration());
        }
        //?}
        if (packet instanceof ClientboundHurtAnimationPacket hurt) {
            return new ActorEvent.Hurt(t, hurt.yaw());
        }
        if (packet instanceof ClientboundDamageEventPacket) {
            return new ActorEvent.Hurt(t, 0f);
        }
        if (packet instanceof ClientboundEntityEventPacket ev) {
            byte id = ev.getEventId();
            return REPLAYED_ENTITY_EVENTS.contains(id) ? new ActorEvent.EntityEvent(t, id) : null;
        }
        return null;
    }

    public static Packet<?> swingPacket(int entityId, ActorEvent.Swing swing) {
        //? if >=26.3 {
        SwingAnimation animation = SwingAnimation.DEFAULT;
        SwingAnimationType[] types = SwingAnimationType.values();
        if (swing.animType() >= 0 && swing.animType() < types.length) {
            animation = new SwingAnimation(types[swing.animType()], Math.max(1, swing.duration()));
        }
        return new ClientboundSwingAnimationPacket(entityId, swing.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, animation);
        //?} else {
        /*return PacketFactory.animate(entityId, swing.offHand() ? ClientboundAnimatePacket.SWING_OFF_HAND : ClientboundAnimatePacket.SWING_MAIN_HAND);
        *///?}
    }

    /** Makes a real entity swing its arm (broadcast to everyone tracking it). */
    public static void swingEntity(net.minecraft.world.entity.LivingEntity entity, boolean offHand) {
        InteractionHand hand = offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        //? if >=26.3 {
        entity.swing(hand, SwingAnimation.DEFAULT, true);
        //?} else {
        /*entity.swing(hand, true);
        *///?}
    }

    /** Detaches a fake player's advancement tracker from the game's criteria triggers. */
    public static void stopAdvancements(net.minecraft.server.PlayerAdvancements advancements) {
        //? if >=26.2 {
        advancements.clearTriggers();
        //?} else {
        /*advancements.stopListening();
        *///?}
    }

    public static Packet<?> animatePacket(int entityId, int kind) {
        int action = switch (kind) {
            case ActorEvent.ANIM_CRIT -> ClientboundAnimatePacket.CRITICAL_HIT;
            case ActorEvent.ANIM_MAGIC_CRIT -> ClientboundAnimatePacket.MAGIC_CRITICAL_HIT;
            default -> ClientboundAnimatePacket.WAKE_UP;
        };
        return PacketFactory.animate(entityId, action);
    }
}
