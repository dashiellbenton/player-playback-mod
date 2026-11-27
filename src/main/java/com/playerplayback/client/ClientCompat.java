package com.playerplayback.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client-side API differences between supported versions. */
final class ClientCompat {
    private ClientCompat() {}

    static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else {
        /*return mc.screen;
        *///?}
    }

    static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        mc.gui.setScreen(screen);
        //?} else {
        /*mc.setScreen(screen);
        *///?}
    }

    static InputConstants.Type keyboard() {
        //? if >=26.3 {
        return InputConstants.Type.KEYBOARD;
        //?} else {
        /*return InputConstants.Type.KEYSYM;
        *///?}
    }

    static void message(Minecraft mc, Component text) {
        if (mc.player == null) return;
        //? if >=26.1 {
        mc.player.sendSystemMessage(text);
        //?} else {
        /*mc.player.displayClientMessage(text, false);
        *///?}
    }
}
