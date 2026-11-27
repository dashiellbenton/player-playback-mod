package com.playerplayback.client;

import com.playerplayback.network.GuiState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Edits how an actor looks: name, skin and nametag. With a scene, the changes only apply to
 * that recording inside that scene; without one they're the recording's defaults.
 */
public class ActorEditScreen extends Screen {
    private final Screen parent;
    private final String recording;
    private final String scene;
    private String nameValue = "";
    private String skinValue = "";
    private boolean initialized;

    public ActorEditScreen(Screen parent, String recording, String scene) {
        super(Component.literal("Edit actor"));
        this.parent = parent;
        this.recording = recording;
        this.scene = scene;
    }

    private String sceneArg() {
        return scene == null ? "" : scene;
    }

    @Override
    protected void init() {
        String actorName = null, skin = null;
        Boolean hidden = null;
        GuiState s = ClientState.get();
        if (scene == null) {
            for (GuiState.Rec r : s.recordings) if (r.name.equals(recording)) {
                actorName = r.actorName;
                skin = r.skin;
                hidden = r.hideNametag;
            }
        } else {
            for (GuiState.SceneInfo sc : s.scenes) if (sc.name.equals(scene)) for (GuiState.Entry e : sc.entries) if (e.recording.equals(recording)) {
                actorName = e.actorName;
                skin = e.skin;
                hidden = e.hideNametag;
            }
        }
        if (!initialized) {
            nameValue = actorName == null ? "" : actorName;
            initialized = true;
        }

        int w = Math.min(width - 20, 320);
        int x = (width - w) / 2;
        int y = 10;
        addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal("Actor: " + recording).withStyle(ChatFormatting.BOLD), font));
        y += 14;
        addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal(scene == null
                ? "Defaults for this recording (everywhere it plays)"
                : "Only inside scene '" + scene + "'").withStyle(ChatFormatting.GRAY), font));
        y += 22;

        // Name
        addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal("Name (max 16): " + (actorName == null ? "original" : actorName)), font));
        y += 14;
        EditBox name = new EditBox(font, x, y, w - 128, 20, Component.literal("Name"));
        name.setMaxLength(16);
        name.setValue(nameValue);
        name.setResponder(v -> nameValue = v);
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal("Set"), b -> ClientState.send("set_name", recording, sceneArg(), nameValue.trim()))
                .bounds(x + w - 124, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            nameValue = "";
            ClientState.send("set_name", recording, sceneArg(), "");
        }).bounds(x + w - 60, y, 60, 20).build());
        y += 30;

        // Skin
        addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal("Skin: " + (skin == null ? "original" : skin)), font));
        y += 14;
        EditBox skinBox = new EditBox(font, x, y, w - 128, 20, Component.literal("Username"));
        skinBox.setMaxLength(16);
        skinBox.setValue(skinValue);
        skinBox.setHint(Component.literal("any Minecraft username").withStyle(ChatFormatting.DARK_GRAY));
        skinBox.setResponder(v -> skinValue = v);
        addRenderableWidget(skinBox);
        addRenderableWidget(Button.builder(Component.literal("Use skin"), b -> {
            if (!skinValue.isBlank()) ClientState.send("skin_user", recording, sceneArg(), skinValue.trim());
        }).bounds(x + w - 124, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Original"), b -> ClientState.send("skin_original", recording, sceneArg()))
                .bounds(x + w - 60, y, 60, 20).build());
        y += 30;

        // Nametag
        String tagText = hidden == null ? (scene == null ? "Shown" : "Default") : hidden ? "Hidden" : "Shown";
        Boolean current = hidden;
        addRenderableWidget(Button.builder(Component.literal("Nametag: " + tagText), b -> {
            String next;
            if (scene == null) next = Boolean.TRUE.equals(current) ? "show" : "hide";
            else next = current == null ? "hide" : current ? "show" : "reset";
            ClientState.send("nametag", recording, sceneArg(), next);
        }).bounds(x, y, w, 20).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 50, Math.max(y, height - 30), 100, 20).build());
    }

    public void refresh() {
        rebuildWidgets();
    }

    @Override
    public void added() {
        super.added();
        ClientState.send("open");
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(minecraft, parent instanceof PlaybackScreen ? new PlaybackScreen() : parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
