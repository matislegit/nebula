package com.example.nebula;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Hides your real username everywhere the game draws text (tab list, chat, name tags, menus, scoreboards,
 * signs, toasts, ...) and shows another name instead. Only changes what you see on your own screen.
 * The swap itself is done by StringDecomposerMixin + {@link NameProtectSink}, which every piece of text passes through.
 */
public final class NameProtectModule implements NebulaModule {
    // read by the mixin on every piece of text, so keep them simple and fast
    public static volatile boolean active;
    public static volatile String realName = "";
    public static volatile int[] realLower = new int[0];
    public static volatile int[] fakeCps = new int[0];

    private static final int PAD = 6;
    private static final int ROW_H = 24;

    private final NebulaConfig cfg;
    private String text;
    private EditBox box;
    private int lx, ly, lw;

    public NameProtectModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.text = cfg.nameProtectName;
        refresh(cfg);
    }

    @Override public String name() { return "Name Protect"; }
    @Override public Category category() { return Category.MISC; }
    @Override public boolean isEnabled() { return cfg.nameProtectEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.nameProtectEnabled = enabled;
        refresh(cfg);
        cfg.save();
    }

    // ------------------------------------------------------------ the swap data
    private static String lookupRealName() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int[] codePoints(String s, boolean lower) {
        int[] out = s.codePoints().toArray();
        if (lower) for (int i = 0; i < out.length; i++) out[i] = Character.toLowerCase(out[i]);
        return out;
    }

    public static void refresh(NebulaConfig cfg) {
        String real = lookupRealName();
        String fake = cfg.nameProtectName == null || cfg.nameProtectName.isBlank() ? "Nebula" : cfg.nameProtectName;
        realName = real;
        realLower = codePoints(real, true);
        fakeCps = codePoints(fake, false);
        active = cfg.nameProtectEnabled && !real.isEmpty() && !fake.equalsIgnoreCase(real);
    }

    /** Quick check used by the mixin: could this text contain the real name at all? */
    public static boolean mayContain(String text) {
        if (!active) return false;
        String real = realName;
        int n = real.length();
        if (n == 0 || text.length() < n) return false;
        char lowerFirst = Character.toLowerCase(real.charAt(0));
        char upperFirst = Character.toUpperCase(real.charAt(0));
        int last = text.length() - n;
        for (int i = 0; i <= last; i++) {
            char c = text.charAt(i);
            if ((c == lowerFirst || c == upperFirst) && text.regionMatches(true, i, real, 0, n)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public void initWidgets(NebulaScreen screen, Font font) {
        box = new EditBox(font, 0, 0, 80, 9, Component.literal("Name"));
        box.setBordered(false);
        box.setMaxLength(24);
        box.setTextColor(0xFF23272E);
        box.setValue(text);
        box.setResponder(s -> {
            text = s;
            cfg.nameProtectName = s;
            refresh(cfg);               // applies instantly while you type
        });
        screen.addModuleWidget(box);
    }

    @Override
    public void onScreenClosed() {
        cfg.save();
    }

    @Override
    public int contentHeight() {
        return PAD + ROW_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;
        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, ROW_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Name", rx + 8, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);

        int bx = rx + 46;
        int bw = rw - 46 - 8;
        Ui.rrect(g, bx - 5, y1 + 3, bw + 10, ROW_H - 6, 5, Ui.ROW);
        if (box != null) {
            box.setPosition(bx, y1 + (ROW_H - 9) / 2 + 1);
            box.setWidth(bw);
            if (text.isEmpty() && !box.isFocused()) {
                Ui.text(g, font, "Nebula", bx, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT_DIM);
            } else {
                box.render(g, mx, my, 0f);
            }
        }
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0 || box == null) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int y1 = ly + PAD;
        if (Ui.in(mx, my, rx + 41, y1 + 3, rw - 41 - 3, ROW_H - 6)) {
            screen.focusWidget(box);
            return true;
        }
        return false;
    }

    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
