package com.playerplayback.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Client -> server: a GUI button press. */
public record ActionPayload(String action, List<String> args) implements CustomPacketPayload {
    public static final Type<ActionPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("playerplayback", "action"));
    public static final StreamCodec<ByteBuf, ActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), ActionPayload::action,
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(16)), ActionPayload::args,
            ActionPayload::new);

    public static ActionPayload of(String action, String... args) {
        return new ActionPayload(action, List.of(args));
    }

    public String arg(int i) {
        return i < args.size() ? args.get(i) : "";
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
