package com.playerplayback.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.playerplayback.network.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}

public class PlayerPlaybackClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("playerplayback", "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        KeyMapping key = new KeyMapping("key.playerplayback.open", ClientCompat.keyboard(), InputConstants.KEY_P, CATEGORY);
        //? if >=26.1 {
        openKey = KeyMappingHelper.registerKeyMapping(key);
        //?} else {
        /*openKey = KeyBindingHelper.registerKeyBinding(key);
        *///?}

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> {
            ClientState.update(payload.json());
            var current = ClientCompat.screen(Minecraft.getInstance());
            if (current instanceof PlaybackScreen screen) screen.refresh();
            else if (current instanceof ActorEditScreen screen) screen.refresh();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player == null) continue;
                if (!ClientState.serverHasMod()) {
                    ClientCompat.message(client, Component.literal("[Playback] This server doesn't have Player Playback installed.")
                            .withStyle(ChatFormatting.RED));
                    continue;
                }
                ClientCompat.setScreen(client, new PlaybackScreen());
            }
        });
    }
}
