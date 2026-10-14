package com.example.nebula;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shows a list of every enabled module on one side of the screen. Setting: left / right. */
public final class HudModule implements NebulaModule {
    private static final int PAD = 6;
    private static final int ROW_H = 24;
    private static final int SEG_W = 100;
    private static final int SEG_H = 16;

    private final NebulaConfig cfg;
    private int lx, ly, lw;
    private float sideAnim;

    public HudModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.sideAnim = cfg.hudRight ? 1f : 0f;
    }

    @Override public String name() { return "HUD"; }
    @Override public Category category() { return Category.CLIENT; }
    @Override public boolean isEnabled() { return cfg.hudEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.hudEnabled = enabled;
        cfg.save();
    }

    // ------------------------------------------------------------ the on-screen list
    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        HudModule hud = NebulaClient.HUD;
        if (hud == null || !hud.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        List<String> names = new ArrayList<>();
        for (NebulaModule m : NebulaClient.MODULES) {
            if (m != hud && m.isEnabled()) names.add(m.name());
        }
        if (names.isEmpty()) return;
        names.sort(Comparator.comparingInt((String s) -> Ui.width(font, s)).reversed());

        boolean right = hud.cfg.hudRight;
        float savedAlpha = Ui.alpha;
        Ui.alpha = 1f;
        int margin = 4;
        int lineH = 13;
        int y = margin;
        for (String name : names) {
            int w = Ui.width(font, name) + 15;
            int x = right ? g.guiWidth() - margin - w : margin;
            Ui.card(g, x, y, w, lineH, 5, 0xCCFFFFFF, 0x55AEB4BC);
            if (right) {
                g.fill(x + w - 4, y + 3, x + w - 2, y + lineH - 3, Ui.ACCENT);     // accent bar on the outer edge
                Ui.text(g, font, name, x + 6, y + (lineH - Ui.FONT_H) / 2 + 1, Ui.TEXT);
            } else {
                g.fill(x + 2, y + 3, x + 4, y + lineH - 3, Ui.ACCENT);
                Ui.text(g, font, name, x + 9, y + (lineH - Ui.FONT_H) / 2 + 1, Ui.TEXT);
            }
            y += lineH + 2;
        }
        Ui.alpha = savedAlpha;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + ROW_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        sideAnim = Ui.approach(sideAnim, cfg.hudRight ? 1f : 0f, 16f);

        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;
        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, ROW_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Side", rx + 8, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);

        int sx = rx + rw - 6 - SEG_W;
        int sy = y1 + (ROW_H - SEG_H) / 2;
        int half = SEG_W / 2;
        Ui.rrect(g, sx, sy, SEG_W, SEG_H, 7, Ui.ROW);
        Ui.rrect(g, sx + 2 + Math.round(sideAnim * (half - 1)), sy + 2, half - 3, SEG_H - 4, 5, Ui.ACCENT);
        Ui.textCenter(g, font, "Left", sx + half / 2, sy + (SEG_H - Ui.FONT_H) / 2 + 1,
                Ui.lerp(0xFFFFFFFF, Ui.TEXT_DIM, sideAnim));
        Ui.textCenter(g, font, "Right", sx + half + half / 2, sy + (SEG_H - Ui.FONT_H) / 2 + 1,
                Ui.lerp(Ui.TEXT_DIM, 0xFFFFFFFF, sideAnim));
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int y1 = ly + PAD;
        int sx = rx + rw - 6 - SEG_W;
        int sy = y1 + (ROW_H - SEG_H) / 2;
        if (Ui.in(mx, my, sx, sy, SEG_W, SEG_H)) {
            boolean right = mx >= sx + SEG_W / 2;
            if (right != cfg.hudRight) {
                cfg.hudRight = right;
                cfg.save();
                Ui.click(1.1f);
            }
            return true;
        }
        return false;
    }

    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
