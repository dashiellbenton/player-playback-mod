package com.playerplayback.playback;

import com.playerplayback.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Network connection for a fake player. It counts as connected (so vanilla code like ender
 * pearls treats the actor as an online player), but everything sent to it is thrown away.
 */
final class FakeConnection extends Connection {
    FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        ((ConnectionAccessor) (Object) this).pp$setChannel(new EmbeddedChannel());
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
    }
}
