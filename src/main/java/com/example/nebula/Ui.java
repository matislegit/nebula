package com.example.nebula;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Drawing helpers: anti-aliased rounded rectangles, bold text, toggles, click sounds. */
public final class Ui {
    private Ui() {}

    public static final int FONT_H = 9;

    // ---- white / gray theme
    public static final int PANEL        = 0xFFF9FAFB;
    public static final int PANEL_BORDER = 0xFFDCE0E5;
    public static final int ROW          = 0xFFEEF0F3;
    public static final int ROW_HOVER    = 0xFFE5E8EC;
    public static final int ROW_OPEN     = 0xFFE8EBEF;
    public static final int CARD         = 0xFFFFFFFF;
    public static final int CARD_HOVER   = 0xFFF0F2F5;
    public static final int CONTENT      = 0xFFF2F4F6;
    public static final int TEXT         = 0xFF23272E;
    public static final int TEXT_DIM     = 0xFF8B929C;
    public static final int ACCENT       = 0xFF3B4048;
    public static final int TRACK_OFF    = 0xFFD2D6DC;
    public static final int KNOB         = 0xFFFFFFFF;

    /** Global alpha multiplier (used for fade-in). */
    public static float alpha = 1f;
    /** Seconds since the last frame (set by the screen). */
    public static float dt = 0.016f;

    // ------------------------------------------------------------ colour math
    public static int a(int argb) {
        int al = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    public static int scaleAlpha(int argb, double f) {
        int al = (int) Math.round(((argb >>> 24) & 0xFF) * f);
        al = Math.max(0, Math.min(255, al));
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static int lerp(int c1, int c2, float t) {
        t = clamp01(t);
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int a = (c1 >>> shift) & 0xFF;
            int b = (c2 >>> shift) & 0xFF;
            out |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }

    /** Exponential smoothing towards target; framerate independent. */
    public static float approach(float cur, float target, float speed) {
        float k = 1f - (float) Math.exp(-speed * dt);
        float v = cur + (target - cur) * k;
        return Math.abs(target - v) < 0.002f ? target : v;
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------ shapes
    /** Rounded rectangle with anti-aliased corners. */
    public static void rrect(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
        int c = a(argb);
        if ((c >>> 24) == 0 || w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, c);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, c);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            double dx = Math.sqrt(r * (double) r - dy * dy);
            double edge = r - dx;
            int full = (int) Math.ceil(edge);
            double cov = full - edge;
            int top = y + i;
            int bot = y + h - 1 - i;
            g.fill(x + full, top, x + w - full, top + 1, c);
            g.fill(x + full, bot, x + w - full, bot + 1, c);
            if (cov > 0.03 && full > 0) {
                int pc = scaleAlpha(c, cov);
                g.fill(x + full - 1, top, x + full, top + 1, pc);
                g.fill(x + w - full, top, x + w - full + 1, top + 1, pc);
                g.fill(x + full - 1, bot, x + full, bot + 1, pc);
                g.fill(x + w - full, bot, x + w - full + 1, bot + 1, pc);
            }
        }
    }

    public static void card(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int border) {
        rrect(g, x - 1, y - 1, w + 2, h + 2, r + 1, border);
        rrect(g, x, y, w, h, r, fill);
    }

    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int i = 8; i >= 1; i--) {
            rrect(g, x - i, y - i + 4, w + 2 * i, h + 2 * i, r + i, 0x08000000);
        }
    }

    public static void toggle(GuiGraphics g, int x, int y, float t) {
        rrect(g, x, y, 28, 14, 7, lerp(TRACK_OFF, ACCENT, t));
        int kx = x + 2 + Math.round(t * 14);
        rrect(g, kx, y + 3, 10, 10, 5, 0x26000000);
        rrect(g, kx, y + 2, 10, 10, 5, KNOB);
    }

    // ------------------------------------------------------------ text (bold)
    public static Component bold(String s) {
        return Component.literal(s).withStyle(ChatFormatting.BOLD);
    }

    public static int width(Font f, String s) {
        return f.width(bold(s));
    }

    private static final Map<String, Integer> WIDTHS = new HashMap<>();

    /** Same as width(), but remembered. Only for a small fixed set of strings (like module names). */
    public static int cachedWidth(Font f, String s) {
        return WIDTHS.computeIfAbsent(s, k -> f.width(bold(k)));
    }

    /** Draws a component that was built once and reused (no per-frame allocation). */
    public static void textC(GuiGraphics g, Font f, Component c, int x, int y, int argb) {
        g.drawString(f, c, x, y, a(argb), false);
    }

    public static void text(GuiGraphics g, Font f, String s, int x, int y, int argb) {
        g.drawString(f, bold(s), x, y, a(argb), false);
    }

    public static void textRight(GuiGraphics g, Font f, String s, int rightX, int y, int argb) {
        text(g, f, s, rightX - width(f, s), y, argb);
    }

    public static void textCenter(GuiGraphics g, Font f, String s, int cx, int y, int argb) {
        text(g, f, s, cx - width(f, s) / 2, y, argb);
    }

    public static String fit(Font f, String s, int maxW) {
        if (width(f, s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && width(f, t + "...") > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    // ------------------------------------------------------------ sound
    public static void click(float pitch) {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }
}
