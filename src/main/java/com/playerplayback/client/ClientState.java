package com.playerplayback.client;

import com.google.gson.Gson;
import com.playerplayback.network.ActionPayload;
import com.playerplayback.network.GuiState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Latest state received from the server, plus helpers to send GUI actions. */
public final class ClientState {
    private static final Gson GSON = new Gson();
    private static GuiState state = new GuiState();
    private static boolean received;

    private ClientState() {}

    public static GuiState get() {
        return state;
    }

    public static boolean hasReceived() {
        return received;
    }

    static void update(String json) {
        GuiState s = GSON.fromJson(json, GuiState.class);
        state = s == null ? new GuiState() : s;
        received = true;
    }

    static void reset() {
        state = new GuiState();
        received = false;
    }

    public static boolean serverHasMod() {
        return ClientPlayNetworking.canSend(ActionPayload.TYPE);
    }

    public static void send(String action, String... args) {
        if (serverHasMod()) ClientPlayNetworking.send(ActionPayload.of(action, args));
    }
}
