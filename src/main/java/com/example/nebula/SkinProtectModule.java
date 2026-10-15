package com.example.nebula;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Shows another player's skin on your own character (body, arms, cape) instead of your real one.
 * Right-click the module to type the username. The skin is looked up with Minecraft's own skin cache.
 *
 * This only changes what YOU see (great for streaming / recording). Other players still see your real skin.
 * The swap itself happens in AbstractClientPlayerMixin, which asks {@link #currentSkin()}.
 */
public final class SkinProtectModule implements NebulaModule {
    /** Filled in by PlayerSkinRenderCacheMixin when the game creates its skin cache. */
    public static volatile PlayerSkinRenderCache cache;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final long DEBOUNCE_NANOS = 600_000_000L;

    private static final int PAD = 6;
    private static final int ROW_H = 24;
    private static final int SP = 6;
    private static final int STATUS_H = 16;

    private final NebulaConfig cfg;

    // what is typed in the box, and when it was last changed
    private String text;
    private boolean pending;
    private long lastEdit;

    // the skin currently being shown
    private String appliedName;
    private ResolvableProfile profile;
    private CompletableFuture<?> lookup;

    // gui
    private EditBox box;
    private int lx, ly, lw;

    public SkinProtectModule(NebulaConfig cfg) {
        this.cfg = cfg;
        this.text = cfg.skinProtectName;
    }

    @Override public String name() { return "Skin Protect"; }
    @Override public Category category() { return Category.MISC; }
    @Override public boolean isEnabled() { return cfg.skinProtectEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.skinProtectEnabled = enabled;
        cfg.save();
    }

    // ------------------------------------------------------------ the skin
    /** The skin to draw on the local player, or null to leave things alone. Called very often by the mixin. */
    public PlayerSkin currentSkin() {
        PlayerSkinRenderCache c = cache;
        if (c == null) return null;
        String name = cfg.skinProtectName;
        if (profile == null || !name.equals(appliedName)) {
            appliedName = name;
            profile = ResolvableProfile.createUnresolved(name);
            lookup = c.lookup(profile);          // starts the download right away
        }
        var info = c.getOrDefault(profile);       // the default skin until the real one has loaded
        return info.playerSkin();
    }

    /** Applies the typed name a moment after you stop typing (so we don't ask Mojang on every key press). */
    public void tick(Minecraft mc) {
        if (pending && System.nanoTime() - lastEdit > DEBOUNCE_NANOS) commit();
    }

    private void commit() {
        pending = false;
        if (VALID.matcher(text).matches() && !text.equals(cfg.skinProtectName)) {
            cfg.skinProtectName = text;
            cfg.save();
        }
    }

    private String status() {
        if (!VALID.matcher(text).matches()) return "3-16 letters, numbers or _";
        if (pending) return "Typing...";
        if (profile == null || lookup == null) return "Skin: " + cfg.skinProtectName;
        if (!lookup.isDone()) return "Loading " + cfg.skinProtectName + "...";
        try {
            Object result = lookup.getNow(null);
            if (result instanceof Optional<?> o && o.isPresent()) return "Showing " + cfg.skinProtectName;
            return "User not found";
        } catch (Throwable t) {
            return "Lookup failed";
        }
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public void initWidgets(NebulaScreen screen, Font font) {
        box = new EditBox(font, 0, 0, 80, 9, Component.literal("Username"));
        box.setBordered(false);
        box.setMaxLength(16);
        box.setTextColor(0xFF23272E);
        box.setFilter(s -> s.matches("[A-Za-z0-9_]*"));
        box.setValue(text);
        box.setResponder(s -> {
            text = s;
            pending = true;
            lastEdit = System.nanoTime();
        });
        screen.addModuleWidget(box);
    }

    @Override
    public void onScreenClosed() {
        if (pending) commit();
    }

    @Override
    public int contentHeight() {
        return PAD + ROW_H + SP + STATUS_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;

        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, ROW_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "User", rx + 8, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);

        int bx = rx + 46;
        int bw = rw - 46 - 8;
        Ui.rrect(g, bx - 5, y1 + 3, bw + 10, ROW_H - 6, 5, Ui.ROW);
        if (box != null) {
            box.setPosition(bx, y1 + (ROW_H - 9) / 2 + 1);
            box.setWidth(bw);
            if (text.isEmpty() && !box.isFocused()) {
                Ui.text(g, font, "Username", bx, y1 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT_DIM);
            } else {
                box.render(g, mx, my, 0f);
            }
        }

        int y2 = y1 + ROW_H + SP;
        Ui.text(g, font, Ui.fit(font, status(), rw - 8), rx + 4, y2 + 4, Ui.TEXT_DIM);
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
