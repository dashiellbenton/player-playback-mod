package com.playerplayback.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: everything the GUI shows, as JSON (see {@link GuiState}). */
public record StatePayload(String json) implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("playerplayback", "state"));
    public static final StreamCodec<ByteBuf, StatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(1 << 21), StatePayload::json, StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
