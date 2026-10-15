package com.example.nebula;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

/**
 * Marks the chunk of every other player your client can currently see (the same chunk as you or chunks near you)
 * with a rainbow layer at Y 45, optionally with tracer lines to them. When a player is first detected it plays a
 * sound and shows a notification with the chunk coordinates. It only uses players your game already knows about,
 * so it works out to your entity tracking distance.
 */
public final class LivePlayerDetectorModule implements NebulaModule {
    private static final long GRACE_MS = 8_000L;      // a player must be gone this long before they count as "new" again

    private static final int PAD = 6;
    private static final int ROW_H = 22;

    private final NebulaConfig cfg;
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private Object lastLevel;

    /** Chunk keys with a player in them (filled by collect, read by the renderer). */
    public final long[] chunks = new long[64];
    public int chunkCount;

    // gui
    private int lx, ly, lw;
    private float tracerAnim;

    public LivePlayerDetectorModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.tracerAnim = cfg.liveTracers ? 1f : 0f;
    }

    @Override public String name() { return "Live Player Detector"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.liveEnabled; }
    public boolean tracers() { return cfg.liveTracers; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.liveEnabled = enabled;
        if (!enabled) lastSeen.clear();
        cfg.save();
    }

    /** Client tick: spots players that just came into range and alerts you. */
    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level != lastLevel) {
            lastSeen.clear();
            lastLevel = level;
        }
        if (!cfg.liveEnabled || level == null || self == null) return;

        long now = System.currentTimeMillis();
        boolean found = false;
        for (AbstractClientPlayer p : level.players()) {
            if (p == self) continue;
            UUID id = p.getUUID();
            Long last = lastSeen.get(id);
            if (last == null || now - last > GRACE_MS) {
                int cx = p.getBlockX() >> 4;
                int cz = p.getBlockZ() >> 4;
                NotificationsModule.push("Live Player Detected",
                        "Chunk " + cx + ", " + cz + "  (X " + (cx * 16) + ", Z " + (cz * 16) + ")",
                        NotificationsModule.RAINBOW);
                found = true;
            }
            lastSeen.put(id, now);
        }
        if (found) NotificationsModule.playLiveSound();
        if (lastSeen.size() > 64) lastSeen.values().removeIf(t -> now - t > 120_000L);
    }

    /** Render thread: which chunks currently contain another player. */
    public void collect(ClientLevel level, LocalPlayer self) {
        chunkCount = 0;
        for (AbstractClientPlayer p : level.players()) {
            if (p == self) continue;
            long k = ((p.getBlockX() >> 4) & 0xFFFFFFFFL) | ((long) (p.getBlockZ() >> 4) << 32);
            boolean duplicate = false;
            for (int i = 0; i < chunkCount; i++) {
                if (chunks[i] == k) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate && chunkCount < chunks.length) chunks[chunkCount++] = k;
        }
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + ROW_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        tracerAnim = Ui.approach(tracerAnim, cfg.liveTracers ? 1f : 0f, 16f);
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;
        int y1 = y + PAD;
        boolean hover = Ui.in(mx, my, rx, y1, rw, ROW_H);
        Ui.card(g, rx, y1, rw, ROW_H, 6, hover ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Tracers", rx + 8, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);
        Ui.toggle(g, rx + rw - 8 - 28, y1 + (ROW_H - 14) / 2, tracerAnim);
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        if (Ui.in(mx, my, lx + PAD, ly + PAD, lw - 2 * PAD, ROW_H)) {
            cfg.liveTracers = !cfg.liveTracers;
            Ui.click(cfg.liveTracers ? 1.2f : 0.85f);
            cfg.save();
            return true;
        }
        return false;
    }

    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
