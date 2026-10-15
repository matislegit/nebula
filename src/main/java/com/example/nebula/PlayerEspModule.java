package com.example.nebula;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Highlights other players through blocks. Three styles: Filled Box, Box (outline only) or Skeleton.
 * The colour (default purple) and opacity can be changed. Drawn by {@link EspRenderer}.
 */
public final class PlayerEspModule implements NebulaModule {
    public static final int FILLED = 0;
    public static final int BOX = 1;
    public static final int SKELETON = 2;

    private static final String[] MODES = {"Filled Box", "Box", "Skeleton"};
    private static final int[] SWATCHES = {0xA03CF0, 0xEC4899, 0xEF4444, 0xF59E0B, 0xEAB308, 0x22C55E, 0x06B6D4, 0x3B82F6};

    private static final int PAD = 6;
    private static final int TYPE_H = 44;
    private static final int COLOR_H = 58;
    private static final int OPAC_H = 30;
    private static final int SP = 6;

    private final NebulaConfig cfg;
    private final List<AbstractClientPlayer> targets = new ArrayList<>();

    // gui
    private int lx, ly, lw;
    private int dragging;            // 0 none, 1 hue slider, 2 opacity slider
    private float modeAnim;

    public PlayerEspModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.modeAnim = cfg.playerEspMode;
    }

    @Override public String name() { return "Player ESP"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.playerEspEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.playerEspEnabled = enabled;
        cfg.save();
    }

    public int mode() { return cfg.playerEspMode; }
    public int color() { return cfg.playerEspColor & 0xFFFFFF; }
    public float opacity() { return cfg.playerEspOpacity; }

    /** Every other player the client currently knows about. The list is reused, so don't keep it. */
    public List<AbstractClientPlayer> collect(ClientLevel level, LocalPlayer self) {
        targets.clear();
        for (AbstractClientPlayer p : level.players()) {
            if (p != self && !p.isRemoved()) targets.add(p);
        }
        return targets;
    }

    // ------------------------------------------------------------ colour helpers
    private static float hueOf(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if (d < 1e-5f) return 0f;
        float h;
        if (max == r) h = ((g - b) / d) % 6f;
        else if (max == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h /= 6f;
        return h < 0 ? h + 1f : h;
    }

    private static int fromHue(float h) {
        return Mth.hsvToRgb(Ui.clamp01(h) * 0.9999f, 0.75f, 1.0f) & 0xFFFFFF;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + TYPE_H + SP + COLOR_H + SP + OPAC_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        modeAnim = Ui.approach(modeAnim, cfg.playerEspMode, 16f);
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;

        // ---- type selector
        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, TYPE_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Type", rx + 8, y1 + 5, Ui.TEXT);
        int sx = rx + 8;
        int sw = rw - 16;
        int sy = y1 + 22;
        int segW = sw / 3;
        Ui.rrect(g, sx, sy, segW * 3, 16, 7, Ui.ROW);
        Ui.rrect(g, sx + 2 + Math.round(modeAnim * segW), sy + 2, segW - 3, 12, 5, Ui.ACCENT);
        for (int i = 0; i < 3; i++) {
            float t = 1f - Math.min(1f, Math.abs(modeAnim - i));
            Ui.textCenter(g, font, MODES[i], sx + i * segW + segW / 2, sy + (16 - Ui.FONT_H) / 2 + 1,
                    Ui.lerp(Ui.TEXT_DIM, 0xFFFFFFFF, t));
        }

        // ---- colour: preset swatches + a hue slider
        int y2 = y1 + TYPE_H + SP;
        Ui.card(g, rx, y2, rw, COLOR_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Color", rx + 8, y2 + 5, Ui.TEXT);
        int current = color();
        int swatch = 14;
        int gap = (sw - 8 * swatch) / 7;
        for (int i = 0; i < 8; i++) {
            int cx = sx + i * (swatch + gap);
            int cy = y2 + 19;
            if (SWATCHES[i] == current) Ui.rrect(g, cx - 2, cy - 2, swatch + 4, swatch + 4, 6, Ui.ACCENT);
            Ui.rrect(g, cx, cy, swatch, swatch, 4, 0xFF000000 | SWATCHES[i]);
        }
        int hy = y2 + 41;
        int seg = 4;
        int n = sw / seg;
        for (int k = 0; k < n; k++) {
            g.fill(sx + k * seg, hy, sx + k * seg + seg, hy + 7, Ui.a(0xFF000000 | fromHue(k / (float) n)));
        }
        int kx = sx + Math.round(hueOf(current) * (n * seg)) - 5;
        Ui.rrect(g, kx - 1, hy - 2, 12, 11, 5, 0x33000000);
        Ui.rrect(g, kx, hy - 2, 10, 11, 5, 0xFF000000 | current);
        Ui.rrect(g, kx + 2, hy, 6, 7, 3, 0xFFFFFFFF);

        // ---- opacity
        int y3 = y2 + COLOR_H + SP;
        Ui.card(g, rx, y3, rw, OPAC_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Opacity", rx + 8, y3 + 5, Ui.TEXT);
        Ui.textRight(g, font, Math.round(cfg.playerEspOpacity * 100) + "%", rx + rw - 8, y3 + 5, Ui.TEXT_DIM);
        float frac = (cfg.playerEspOpacity - 0.05f) / 0.95f;
        Ui.rrect(g, sx, y3 + 19, sw, 4, 2, Ui.TRACK_OFF);
        Ui.rrect(g, sx, y3 + 19, Math.max(4, Math.round(frac * sw)), 4, 2, Ui.ACCENT);
        int ok = sx + Math.round(frac * sw) - 5;
        Ui.rrect(g, ok - 1, y3 + 17, 12, 12, 6, 0x22000000);
        Ui.rrect(g, ok, y3 + 17, 10, 10, 5, Ui.KNOB);
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int sx = rx + 8;
        int sw = rw - 16;
        int y1 = ly + PAD;
        int y2 = y1 + TYPE_H + SP;
        int y3 = y2 + COLOR_H + SP;

        // type
        int segW = sw / 3;
        if (Ui.in(mx, my, sx, y1 + 22, segW * 3, 16)) {
            int idx = Math.max(0, Math.min(2, (int) ((mx - sx) / segW)));
            if (idx != cfg.playerEspMode) {
                cfg.playerEspMode = idx;
                Ui.click(1.1f);
                cfg.save();
            }
            return true;
        }
        // swatches
        int swatch = 14;
        int gap = (sw - 8 * swatch) / 7;
        for (int i = 0; i < 8; i++) {
            int cx = sx + i * (swatch + gap);
            if (Ui.in(mx, my, cx - 2, y2 + 17, swatch + 4, swatch + 4)) {
                cfg.playerEspColor = SWATCHES[i];
                Ui.click(1.15f);
                cfg.save();
                return true;
            }
        }
        // hue slider
        if (Ui.in(mx, my, sx - 4, y2 + 37, sw + 8, 15)) {
            dragging = 1;
            dragContent(mx, my);
            return true;
        }
        // opacity
        if (Ui.in(mx, my, rx, y3, rw, OPAC_H)) {
            dragging = 2;
            dragContent(mx, my);
            return true;
        }
        return false;
    }

    @Override
    public void dragContent(double mx, double my) {
        if (dragging == 0) return;
        int sx = lx + PAD + 8;
        int sw = lw - 2 * PAD - 16;
        float v = Ui.clamp01((float) ((mx - sx) / sw));
        if (dragging == 1) {
            int n = sw / 4;
            cfg.playerEspColor = fromHue(v * (n * 4) / sw);
        } else {
            cfg.playerEspOpacity = 0.05f + v * 0.95f;
        }
    }

    @Override
    public void releaseContent() {
        if (dragging != 0) {
            dragging = 0;
            cfg.save();
        }
    }
}
