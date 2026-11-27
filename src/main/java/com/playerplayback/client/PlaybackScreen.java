package com.playerplayback.client;

import com.playerplayback.network.GuiState;
import com.playerplayback.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Main Player Playback screen (default key: P). Built only from vanilla widgets so it works the
 * same on every supported version.
 */
public class PlaybackScreen extends Screen {
    enum Tab { RECORDINGS, SCENES, PLAYING }

    private static final int ROW = 22;

    // Remembered between openings / refreshes.
    private static Tab tab = Tab.RECORDINGS;
    private static int page;
    private static String recName = "";
    private static boolean recBlocks;
    private static String recScene;
    private static boolean loop;
    private static String selectedScene;
    private static String newSceneName = "";
    private static int addRecIndex;
    private static String addDelay = "0";
    private static String pendingDelete;

    private int left, contentWidth;

    public PlaybackScreen() {
        super(Component.literal("Player Playback"));
    }

    @Override
    protected void init() {
        contentWidth = Math.min(width - 20, 460);
        left = (width - contentWidth) / 2;

        addRenderableWidget(new StringWidget(left, 6, contentWidth, 12,
                Component.literal("Player Playback").withStyle(ChatFormatting.BOLD), font));

        int tabW = 90;
        addTab(Tab.RECORDINGS, "Recordings", left, tabW);
        addTab(Tab.SCENES, "Scenes", left + tabW + 4, tabW);
        addTab(Tab.PLAYING, "Playing (" + ClientState.get().playing.size() + ")", left + 2 * (tabW + 4), tabW);
        addRenderableWidget(Button.builder(Component.literal("Stop all").withStyle(ChatFormatting.RED), b -> ClientState.send("stop_all"))
                .bounds(left + contentWidth - 70, 22, 70, 20).build());

        int y = 50;
        switch (tab) {
            case RECORDINGS -> initRecordings(y);
            case SCENES -> initScenes(y);
            case PLAYING -> initPlaying(y);
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 50, height - 24, 100, 20).build());
    }

    private void addTab(Tab t, String label, int x, int w) {
        Component text = t == tab ? Component.literal(label).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.YELLOW) : Component.literal(label);
        addRenderableWidget(Button.builder(text, b -> {
            tab = t;
            page = 0;
            rebuildWidgets();
        }).bounds(x, 22, w, 20).build());
    }

    private void label(int x, int y, int w, Component text) {
        StringWidget sw = new StringWidget(x, y, w, 20, text, font);
        addRenderableWidget(sw);
    }

    private int rowsAvailable(int y) {
        return Math.max(1, (height - 54 - y) / ROW);
    }

    private String myName() {
        return minecraft != null && minecraft.player != null ? Compat.profileName(minecraft.player.getGameProfile()) : "";
    }

    // ------------------------------------------------------------------ recordings tab

    private void initRecordings(int y) {
        GuiState s = ClientState.get();
        GuiState.Active mine = null;
        for (GuiState.Active a : s.recordingNow) if (a.player.equals(myName())) mine = a;

        if (mine != null) {
            label(left, y, contentWidth - 130, Component.literal(String.format(Locale.ROOT, "● Recording '%s'  %.0fs", mine.recording, mine.seconds))
                    .withStyle(ChatFormatting.RED));
            addRenderableWidget(Button.builder(Component.literal("■ Stop & save"), b -> ClientState.send("record_stop"))
                    .bounds(left + contentWidth - 120, y, 120, 20).build());
        } else {
            EditBox name = new EditBox(font, left, y, contentWidth - 250, 20, Component.literal("Recording name"));
            name.setMaxLength(48);
            name.setValue(recName);
            name.setHint(Component.literal("new take name").withStyle(ChatFormatting.DARK_GRAY));
            name.setResponder(v -> recName = v);
            addRenderableWidget(name);
            addRenderableWidget(Button.builder(Component.literal("● Record").withStyle(ChatFormatting.RED), b -> {
                if (!recName.isBlank()) ClientState.send("record_start", recName.trim(), recScene == null ? "" : recScene, String.valueOf(recBlocks));
            }).bounds(left + contentWidth - 245, y, 75, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Blocks: " + (recBlocks ? "On" : "Off")), b -> {
                recBlocks = !recBlocks;
                rebuildWidgets();
            }).bounds(left + contentWidth - 166, y, 66, 20).build());
            addRenderableWidget(Button.builder(Component.literal("With: " + (recScene == null ? "-" : recScene)), b -> {
                recScene = cycleScene(recScene);
                rebuildWidgets();
            }).bounds(left + contentWidth - 96, y, 96, 20).build());
        }
        y += ROW;
        label(left, y, contentWidth - 80, Component.literal("\"With\" plays a scene while you record, then adds the take to it.").withStyle(ChatFormatting.GRAY));
        addRenderableWidget(Button.builder(Component.literal("Loop: " + (loop ? "On" : "Off")), b -> {
            loop = !loop;
            rebuildWidgets();
        }).bounds(left + contentWidth - 70, y, 70, 20).build());
        y += ROW + 4;

        List<GuiState.Rec> recs = s.recordings;
        if (recs.isEmpty()) {
            label(left, y, contentWidth, Component.literal("No recordings yet. Type a name and press Record.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int rows = rowsAvailable(y) - 1;
        int pages = Math.max(1, (recs.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(recs.size(), (page + 1) * rows); i++) {
            GuiState.Rec r = recs.get(i);
            StringBuilder text = new StringBuilder(r.name);
            if (r.actorName != null) text.append("  as ").append(r.actorName);
            if (r.skin != null) text.append("  [skin: ").append(r.skin).append("]");
            if (Boolean.TRUE.equals(r.hideNametag)) text.append("  [no tag]");
            label(left, y, contentWidth - 160, Component.literal(text.toString()));
            addRenderableWidget(Button.builder(Component.literal("▶ Play"), b -> ClientState.send("play", r.name, String.valueOf(loop), "1"))
                    .bounds(left + contentWidth - 155, y, 50, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> ClientCompat.setScreen(minecraft, new ActorEditScreen(this, r.name, null)))
                    .bounds(left + contentWidth - 102, y, 48, 20).build());
            boolean confirming = r.name.equals(pendingDelete);
            addRenderableWidget(Button.builder(Component.literal(confirming ? "Sure?" : "Delete").withStyle(ChatFormatting.RED), b -> {
                if (confirming) {
                    ClientState.send("delete", r.name);
                    pendingDelete = null;
                } else {
                    pendingDelete = r.name;
                }
                rebuildWidgets();
            }).bounds(left + contentWidth - 51, y, 51, 20).build());
            y += ROW;
        }
        pager(y, pages);
    }

    private String cycleScene(String current) {
        List<GuiState.SceneInfo> scenes = ClientState.get().scenes;
        if (scenes.isEmpty()) return null;
        if (current == null) return scenes.get(0).name;
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).name.equals(current)) return i + 1 < scenes.size() ? scenes.get(i + 1).name : null;
        }
        return null;
    }

    private void pager(int y, int pages) {
        if (pages <= 1) return;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        }).bounds(left, y, 20, 20).build());
        label(left + 24, y, 60, Component.literal((page + 1) + "/" + pages));
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page = Math.min(pages - 1, page + 1);
            rebuildWidgets();
        }).bounds(left + 88, y, 20, 20).build());
    }

    // ------------------------------------------------------------------ scenes tab

    private void initScenes(int y) {
        GuiState s = ClientState.get();
        EditBox name = new EditBox(font, left, y, 150, 20, Component.literal("Scene name"));
        name.setMaxLength(48);
        name.setValue(newSceneName);
        name.setHint(Component.literal("new scene name").withStyle(ChatFormatting.DARK_GRAY));
        name.setResponder(v -> newSceneName = v);
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> {
            if (!newSceneName.isBlank()) {
                ClientState.send("scene_create", newSceneName.trim());
                selectedScene = newSceneName.trim();
                newSceneName = "";
            }
        }).bounds(left + 154, y, 56, 20).build());
        y += ROW + 4;

        int listW = 120;
        int sy = y;
        int maxRows = rowsAvailable(y);
        int shown = 0;
        GuiState.SceneInfo selected = null;
        for (GuiState.SceneInfo sc : s.scenes) {
            boolean sel = sc.name.equals(selectedScene);
            if (sel) selected = sc;
            if (shown++ >= maxRows) continue;
            addRenderableWidget(Button.builder(sel ? Component.literal("> " + sc.name).withStyle(ChatFormatting.YELLOW) : Component.literal(sc.name), b -> {
                selectedScene = sc.name;
                page = 0;
                rebuildWidgets();
            }).bounds(left, sy, listW, 20).build());
            sy += ROW;
        }
        if (s.scenes.isEmpty()) {
            label(left, y, contentWidth, Component.literal("No scenes yet. A scene plays many recordings at once.").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (selected == null) {
            label(left + listW + 8, y, contentWidth - listW - 8, Component.literal("Select a scene").withStyle(ChatFormatting.GRAY));
            return;
        }

        int x = left + listW + 8;
        int w = contentWidth - listW - 8;
        GuiState.SceneInfo scene = selected;
        boolean playing = s.playing.stream().anyMatch(p -> scene.name.equals(p.scene));
        int bw = (w - 12) / 4;
        addRenderableWidget(Button.builder(Component.literal("▶ Play").withStyle(ChatFormatting.GREEN),
                b -> ClientState.send("play_scene", scene.name, String.valueOf(loop), "1")).bounds(x, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Loop: " + (loop ? "On" : "Off")), b -> {
            loop = !loop;
            rebuildWidgets();
        }).bounds(x + bw + 4, y, bw, 20).build());
        Button stop = Button.builder(Component.literal("■ Stop"), b -> ClientState.send("stop_scene", scene.name))
                .bounds(x + 2 * (bw + 4), y, bw, 20).build();
        stop.active = playing;
        addRenderableWidget(stop);
        boolean confirming = ("scene:" + scene.name).equals(pendingDelete);
        addRenderableWidget(Button.builder(Component.literal(confirming ? "Sure?" : "Delete").withStyle(ChatFormatting.RED), b -> {
            if (confirming) {
                ClientState.send("scene_delete", scene.name);
                pendingDelete = null;
            } else {
                pendingDelete = "scene:" + scene.name;
            }
            rebuildWidgets();
        }).bounds(x + 3 * (bw + 4), y, bw, 20).build());
        y += ROW;

        // Add row
        List<GuiState.Rec> recs = s.recordings;
        if (!recs.isEmpty()) {
            addRecIndex = Math.floorMod(addRecIndex, recs.size());
            String recToAdd = recs.get(addRecIndex).name;
            addRenderableWidget(Button.builder(Component.literal(recToAdd), b -> {
                addRecIndex++;
                rebuildWidgets();
            }).bounds(x, y, w - 120, 20).build());
            EditBox delay = new EditBox(font, x + w - 116, y, 50, 20, Component.literal("Delay (s)"));
            delay.setValue(addDelay);
            delay.setHint(Component.literal("delay s").withStyle(ChatFormatting.DARK_GRAY));
            delay.setResponder(v -> addDelay = v);
            addRenderableWidget(delay);
            addRenderableWidget(Button.builder(Component.literal("+ Add"), b -> ClientState.send("scene_add", scene.name, recToAdd, addDelay))
                    .bounds(x + w - 62, y, 62, 20).build());
            y += ROW + 2;
        }

        if (scene.entries.isEmpty()) {
            label(x, y, w, Component.literal("Empty. Pick a recording above and press Add.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int rows = rowsAvailable(y) - 1;
        int pages = Math.max(1, (scene.entries.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(scene.entries.size(), (page + 1) * rows); i++) {
            GuiState.Entry e = scene.entries.get(i);
            StringBuilder text = new StringBuilder(e.recording).append(String.format(Locale.ROOT, " @%.1fs", e.delaySeconds));
            if (e.actorName != null) text.append(" as ").append(e.actorName);
            if (Boolean.TRUE.equals(e.hideNametag)) text.append(" [no tag]");
            label(x, y, w - 100, Component.literal(text.toString()));
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> ClientCompat.setScreen(minecraft, new ActorEditScreen(this, e.recording, scene.name)))
                    .bounds(x + w - 96, y, 46, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Remove").withStyle(ChatFormatting.RED), b -> ClientState.send("scene_remove", scene.name, e.recording))
                    .bounds(x + w - 48, y, 48, 20).build());
            y += ROW;
        }
        pager(y, pages);
    }

    // ------------------------------------------------------------------ playing tab

    private void initPlaying(int y) {
        List<GuiState.Play> playing = ClientState.get().playing;
        if (playing.isEmpty()) {
            label(left, y, contentWidth, Component.literal("Nothing is playing.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int rows = rowsAvailable(y);
        int pages = Math.max(1, (playing.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(playing.size(), (page + 1) * rows); i++) {
            GuiState.Play p = playing.get(i);
            String text = "#" + p.id + "  " + (p.scene != null ? "scene " : "") + p.label + "  (" + p.actors + " actor" + (p.actors == 1 ? "" : "s") + ")"
                    + (p.loop ? "  loop" : "") + (p.paused ? "  [paused]" : "");
            label(left, y, contentWidth - 140, Component.literal(text));
            addRenderableWidget(Button.builder(Component.literal(p.paused ? "Resume" : "Pause"),
                    b -> ClientState.send(p.paused ? "resume" : "pause", String.valueOf(p.id))).bounds(left + contentWidth - 134, y, 66, 20).build());
            addRenderableWidget(Button.builder(Component.literal("■ Stop"), b -> ClientState.send("stop", String.valueOf(p.id)))
                    .bounds(left + contentWidth - 64, y, 64, 20).build());
            y += ROW;
        }
        pager(y, pages);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void added() {
        super.added();
        ClientState.send("open");
    }

    public void refresh() {
        rebuildWidgets();
    }

    @Override
    public void removed() {
        ClientState.send("close");
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
