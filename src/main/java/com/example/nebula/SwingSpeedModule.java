package com.example.nebula;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Changes how fast your swing animation plays (punching, mining, placing, using items...), in first and third person.
 * Speed 15 is the normal vanilla speed; lower values play the animation slower, higher values faster.
 * Purely visual: it does not change attack timing, cooldowns or anything the server sees.
 * The work is done by LivingEntityMixin, which asks {@link #duration()}.
 */
public final class SwingSpeedModule implements NebulaModule {
    private static final int PAD = 6;
    private static final int SLIDER_H = 30;
    private static final int MIN = 1;
    private static final int MAX = 30;

    private final NebulaConfig cfg;
    private int lx, ly, lw;
    private boolean sliding;

    public SwingSpeedModule(NebulaConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "Swing Speed"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.swingSpeedEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.swingSpeedEnabled = enabled;
        cfg.save();
    }

    /** Swing length in ticks. Vanilla is 6 ticks at speed 15. */
    public int duration() {
        return Math.max(1, Math.round(6f * 15f / cfg.swingSpeed));
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + SLIDER_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;
        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, SLIDER_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Speed", rx + 8, y1 + 5, Ui.TEXT);
        Ui.textRight(g, font, String.valueOf(cfg.swingSpeed), rx + rw - 8, y1 + 5, Ui.TEXT_DIM);

        int tx = rx + 8;
        int tw = rw - 16;
        float frac = (cfg.swingSpeed - MIN) / (float) (MAX - MIN);
        Ui.rrect(g, tx, y1 + 19, tw, 4, 2, Ui.TRACK_OFF);
        Ui.rrect(g, tx, y1 + 19, Math.max(4, Math.round(frac * tw)), 4, 2, Ui.ACCENT);
        int kx = tx + Math.round(frac * tw) - 5;
        Ui.rrect(g, kx - 1, y1 + 17, 12, 12, 6, 0x22000000);
        Ui.rrect(g, kx, y1 + 17, 10, 10, 5, Ui.KNOB);
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        if (Ui.in(mx, my, lx + PAD, ly + PAD, lw - 2 * PAD, SLIDER_H)) {
            sliding = true;
            dragContent(mx, my);
            return true;
        }
        return false;
    }

    @Override
    public void dragContent(double mx, double my) {
        if (!sliding) return;
        int tx = lx + PAD + 8;
        int tw = lw - 2 * PAD - 16;
        float v = Ui.clamp01((float) ((mx - tx) / tw));
        cfg.swingSpeed = MIN + Math.round(v * (MAX - MIN));
    }

    @Override
    public void releaseContent() {
        if (sliding) {
            sliding = false;
            cfg.save();
        }
    }
}
