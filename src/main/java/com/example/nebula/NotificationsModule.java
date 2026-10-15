package com.example.nebula;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;

/**
 * Pop-up notifications in the bottom corner of the screen, used by Sus Chunk Finder and Live Player Detector.
 * Newest at the bottom, older ones stack above it, at most 5 at once (the oldest is dropped to make room).
 * Each one stays for 10 seconds, then fades out. The only setting is which corner (left / right).
 */
public final class NotificationsModule implements NebulaModule {
    public static final int MAX = 5;
    private static final long SHOW_MS = 10_000L;
    private static final long FADE_OUT_MS = 500L;
    private static final long FADE_IN_MS = 220L;
    private static final long KICK_MS = 260L;          // how long a dropped (oldest) notification takes to vanish

    private static final int H = 38;
    private static final int GAP = 4;
    private static final int MARGIN = 8;

    /** Accent colour value meaning "cycle through rainbow colours". */
    public static final int RAINBOW = -1;

    private static final class Toast {
        final String title;
        final String line;
        final Component titleC;   // built once, reused every frame
        final Component lineC;
        final int w;              // width measured once
        final int accent;
        final long born;
        long dying;               // 0 = alive, otherwise the time it was dropped
        float slot;               // animated position, 0 = bottom

        Toast(String title, String line, Component titleC, Component lineC, int w, int accent, long born) {
            this.title = title;
            this.line = line;
            this.titleC = titleC;
            this.lineC = lineC;
            this.w = w;
            this.accent = accent;
            this.born = born;
        }
    }

    private static final List<Toast> TOASTS = new ArrayList<>();
    private static long lastNanos;

    // two different alert sounds
    private static final SoundEvent LIVE_SOUND = new SoundEvent(
            Identifier.fromNamespaceAndPath("minecraft", "block.note_block.pling"), Optional.empty());
    private static final SoundEvent SUS_SOUND = new SoundEvent(
            Identifier.fromNamespaceAndPath("minecraft", "block.note_block.bell"), Optional.empty());

    private static final int PAD = 6;
    private static final int ROW_H = 24;
    private static final int SEG_W = 100;
    private static final int SEG_H = 16;

    private final NebulaConfig cfg;
    private int lx, ly, lw;
    private float sideAnim;

    public NotificationsModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.sideAnim = cfg.notificationsRight ? 1f : 0f;
    }

    @Override public String name() { return "Notifications"; }
    @Override public Category category() { return Category.CLIENT; }
    @Override public boolean isEnabled() { return cfg.notificationsEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.notificationsEnabled = enabled;
        if (!enabled) TOASTS.clear();
        cfg.save();
    }

    // ------------------------------------------------------------ API for other modules
    /** Shows a notification (does nothing if the Notifications module is switched off). */
    public static void push(String title, String line, int accentRgb) {
        NotificationsModule m = NebulaClient.NOTIFICATIONS;
        if (m == null || !m.isEnabled()) return;
        long now = System.currentTimeMillis();

        // ignore an identical notification that was only just shown
        if (!TOASTS.isEmpty()) {
            Toast last = TOASTS.get(TOASTS.size() - 1);
            if (now - last.born < 1500L && last.title.equals(title) && last.line.equals(line)) return;
        }

        // Only one notification is ever fading out. Anything older that is already fading is removed right away,
        // so even a burst of notifications never leaves a pile of them to draw.
        TOASTS.removeIf(t -> t.dying != 0);
        if (TOASTS.size() >= MAX) TOASTS.get(0).dying = now;      // oldest makes room

        Font font = Minecraft.getInstance().font;
        Component titleC = Ui.bold(title);
        Component lineC = Ui.bold(line);
        int w = Math.max(150, Math.max(font.width(titleC), font.width(lineC)) + 30);
        Toast t = new Toast(title, line, titleC, lineC, w, accentRgb, now);
        t.slot = -0.8f;                                           // slides up from below
        TOASTS.add(t);
    }

    public static void playLiveSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(LIVE_SOUND, 1.5f, 1.0f));
    }

    public static void playSusSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SUS_SOUND, 0.85f, 1.0f));
    }

    // ------------------------------------------------------------ drawing (HUD)
    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        NotificationsModule m = NebulaClient.NOTIFICATIONS;
        if (TOASTS.isEmpty()) return;
        if (m == null || !m.isEnabled()) {
            TOASTS.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        boolean right = m.cfg.notificationsRight;

        long nanos = System.nanoTime();
        float dtSec = lastNanos == 0 ? 0.016f : Math.min(0.05f, (nanos - lastNanos) / 1_000_000_000f);
        lastNanos = nanos;
        float savedDt = Ui.dt;
        float savedAlpha = Ui.alpha;
        Ui.dt = dtSec;

        long now = System.currentTimeMillis();
        TOASTS.removeIf(t -> (t.dying != 0 && now - t.dying >= KICK_MS) || now - t.born >= SHOW_MS + FADE_OUT_MS);

        float hue = (now % 4000L) / 4000f;
        int n = TOASTS.size();
        for (int i = 0; i < n; i++) {
            Toast t = TOASTS.get(i);
            t.slot = Ui.approach(t.slot, n - 1 - i, 14f);      // newest = slot 0 = bottom

            long age = now - t.born;
            float fadeIn = Ui.clamp01(age / (float) FADE_IN_MS);
            float fadeOut = age <= SHOW_MS ? 1f : 1f - Ui.clamp01((age - SHOW_MS) / (float) FADE_OUT_MS);
            float kick = t.dying == 0 ? 1f : 1f - Ui.clamp01((now - t.dying) / (float) KICK_MS);
            float alpha = fadeIn * fadeOut * kick;
            if (alpha <= 0.01f) continue;

            int w = t.w;
            int slide = Math.round((1f - fadeIn) * 16f);
            int x = right ? g.guiWidth() - MARGIN - w + slide : MARGIN - slide;
            int y = g.guiHeight() - MARGIN - H - Math.round(t.slot * (H + GAP));

            int accent = t.accent == RAINBOW
                    ? (0xFF000000 | Mth.hsvToRgb(hue - (float) Math.floor(hue), 0.85f, 1f))
                    : (0xFF000000 | t.accent);

            Ui.alpha = alpha;
            Ui.rrect(g, x - 1, y + 2, w + 2, H + 1, 8, 0x18000000);        // one soft shadow layer (was eight)
            Ui.card(g, x, y, w, H, 7, 0xF7F9FAFB, Ui.PANEL_BORDER);
            Ui.rrect(g, x + 7, y + 7, 3, H - 16, 1, accent);
            Ui.textC(g, font, t.titleC, x + 16, y + 7, Ui.TEXT);
            Ui.textC(g, font, t.lineC, x + 16, y + 19, Ui.TEXT_DIM);

            // time left
            float remain = 1f - Ui.clamp01(age / (float) SHOW_MS);
            int bw = Math.round((w - 20) * remain);
            if (bw > 1) g.fill(x + 10, y + H - 6, x + 10 + bw, y + H - 4, Ui.a(Ui.scaleAlpha(accent, 0.6)));
        }

        Ui.alpha = savedAlpha;
        Ui.dt = savedDt;
    }

    // ------------------------------------------------------------ gui dropdown (left / right)
    @Override
    public int contentHeight() {
        return PAD + ROW_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        sideAnim = Ui.approach(sideAnim, cfg.notificationsRight ? 1f : 0f, 16f);

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
            if (right != cfg.notificationsRight) {
                cfg.notificationsRight = right;
                cfg.save();
                Ui.click(1.1f);
                // show a sample so the new side can be seen right away
                push("Notifications", "They appear here", 0x3B4048);
            }
            return true;
        }
        return false;
    }

    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
