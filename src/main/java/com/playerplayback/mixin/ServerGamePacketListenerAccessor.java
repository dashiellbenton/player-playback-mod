package com.playerplayback.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    /** Fake players never send "client loaded"; without this they'd stay invulnerable. */
    @Invoker("markClientLoaded")
    void pp$markClientLoaded();
}
