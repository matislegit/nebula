package com.example.nebula;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Freecam: the camera flies around on its own while your real character stays put and keeps facing
 * the same way. Mouse look and WASD / jump / sneak control the camera instead of the player.
 * Attacking, eating, using items, the hotbar and your hands all keep working as normal, using the
 * player's real position and direction. Your character's body is visible, and the camera stays inside the
 * chunks that are already loaded around you.
 *
 * How: a Camera mixin overrides the camera position/rotation, an Entity.turn mixin redirects mouse look,
 * and the player's movement input is swapped for an empty one while freecam is on.
 */
public final class FreecamModule implements NebulaModule {
    private static final int PAD = 6;
    private static final int SLIDER_H = 30;
    private static final int ROW_H = 22;
    private static final int SP = 6;

    private final NebulaConfig cfg;

    // freecam state
    private boolean active;
    private LocalPlayer tracked;
    private ClientInput savedInput;
    private double x, y, z;
    private float yaw, pitch;
    private long lastNanos;

    // gui state
    private int lx, ly, lw;
    private boolean sliding;
    private boolean listening;

    public FreecamModule(NebulaConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------ module basics
    @Override public String name() { return "Freecam"; }
    @Override public Category category() { return Category.MISC; }
    @Override public boolean isEnabled() { return active; }
    public boolean isActive() { return active; }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == active) return;
        Minecraft mc = Minecraft.getInstance();
        if (enabled) enable(mc);
        else disable(mc);
    }

    public void toggle(Minecraft mc) {
        if (active) disable(mc);
        else enable(mc);
    }

    private void enable(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        Vec3 eye = p.getEyePosition(1.0f);
        x = eye.x; y = eye.y; z = eye.z;
        yaw = p.getYRot();
        pitch = p.getXRot();
        lastNanos = 0;

        tracked = p;
        savedInput = p.input;
        p.input = new ClientInput();      // empty input: the player can't walk, jump or sneak
        active = true;
    }

    private void disable(Minecraft mc) {
        if (tracked != null && tracked == mc.player && savedInput != null) {
            tracked.input = savedInput;   // give the player their controls back
        }
        tracked = null;
        savedInput = null;
        active = false;
    }

    /** Client tick: switch freecam off if the player died / changed world. */
    public void tick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.player != tracked || mc.level == null || mc.player.isDeadOrDying()) {
            disable(mc);
        }
    }

    // ------------------------------------------------------------ called by the mixins
    /** Mouse look (from Entity.turn) rotates the camera instead of the player. */
    public void onTurn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15f;
        pitch = Math.max(-90f, Math.min(90f, pitch + (float) xRot * 0.15f));
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public double camX() { return x; }
    public double camY() { return y; }
    public double camZ() { return z; }

    /**
     * Moves the camera according to the movement keys. Called once per frame from the Camera mixin.
     * The camera can only move around the area that is already loaded and rendered around the player,
     * so freecam never causes new chunks to be shown.
     */
    public void step(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;

        Options o = mc.options;
        double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double strafe = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
        double vertical = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
        if (forward == 0 && strafe == 0 && vertical == 0) return;

        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double mx = forward * (-Math.sin(yr) * Math.cos(pr)) + strafe * (-Math.cos(yr));
        double my = forward * (-Math.sin(pr)) + vertical;
        double mz = forward * (Math.cos(yr) * Math.cos(pr)) + strafe * (-Math.sin(yr));
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        if (len < 1e-6) return;
        if (len > 1) { mx /= len; my /= len; mz /= len; }

        double dist = cfg.freecamBlocksPerSecond * cfg.freecamSpeed * dt;
        double nx = x + mx * dist;
        double ny = y + my * dist;
        double nz = z + mz * dist;

        ClientLevel level = mc.level;
        LocalPlayer p = tracked;
        if (level == null || p == null) {
            x = nx; y = ny; z = nz;
            return;
        }

        // stay inside the world height
        int minY = level.dimensionType().minY();
        int maxY = minY + level.dimensionType().height();
        y = Math.max(minY + 0.5, Math.min(maxY - 0.5, ny));

        // stay inside the chunks that are loaded and rendered around the player (checked per axis, so you slide along the edge)
        int maxChunks = Math.max(1, mc.options.getEffectiveRenderDistance() - 1);
        int pcx = p.getBlockX() >> 4;
        int pcz = p.getBlockZ() >> 4;
        if (allowed(level, nx, z, pcx, pcz, maxChunks)) x = nx;
        if (allowed(level, x, nz, pcx, pcz, maxChunks)) z = nz;
    }

    private static boolean allowed(ClientLevel level, double wx, double wz, int pcx, int pcz, int maxChunks) {
        int cx = Mth.floor(wx) >> 4;
        int cz = Mth.floor(wz) >> 4;
        if (Math.abs(cx - pcx) > maxChunks || Math.abs(cz - pcz) > maxChunks) return false;
        // the chunk and all 8 neighbours must be loaded, so there are no holes around the camera
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ keybind (shared with Options > Controls)
    private String keyName() {
        KeyMapping km = NebulaClient.FREECAM_KEY;
        if (km == null || km.isUnbound()) return "NONE";
        return km.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    private void bind(InputConstants.Key key) {
        KeyMapping km = NebulaClient.FREECAM_KEY;
        km.setKey(key);
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        Ui.click(1.1f);
    }

    @Override
    public boolean captureKey(int keyCode) {
        if (!listening) return false;
        listening = false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            bind(InputConstants.UNKNOWN);                                   // ESC clears the bind
        } else {
            bind(InputConstants.Type.KEYSYM.getOrCreate(keyCode));
        }
        return true;
    }

    /** Middle click and the side buttons can be bound. Left / right click just cancel the binding. */
    @Override
    public boolean captureMouse(int button) {
        if (!listening || button < 2) return false;
        listening = false;
        bind(InputConstants.Type.MOUSE.getOrCreate(button));
        return true;
    }

    @Override
    public void cancelCapture() {
        listening = false;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + SLIDER_H + SP + ROW_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x0, int y0, int w, int mx, int my) {
        lx = x0; ly = y0; lw = w;
        Ui.rrect(g, x0, y0, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x0 + PAD;
        int rw = w - 2 * PAD;

        // Speed slider (1 - 10)
        int y1 = y0 + PAD;
        Ui.card(g, rx, y1, rw, SLIDER_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Speed", rx + 8, y1 + 5, Ui.TEXT);
        Ui.textRight(g, font, String.format(Locale.ROOT, "%.1fx", cfg.freecamSpeed), rx + rw - 8, y1 + 5, Ui.TEXT_DIM);
        int tx = rx + 8;
        int tw = rw - 16;
        float frac = (cfg.freecamSpeed - 1f) / 9f;
        Ui.rrect(g, tx, y1 + 19, tw, 4, 2, Ui.TRACK_OFF);
        Ui.rrect(g, tx, y1 + 19, Math.max(4, Math.round(frac * tw)), 4, 2, Ui.ACCENT);
        int kx = tx + Math.round(frac * tw) - 5;
        Ui.rrect(g, kx - 1, y1 + 17, 12, 12, 6, 0x22000000);
        Ui.rrect(g, kx, y1 + 17, 10, 10, 5, Ui.KNOB);

        // Keybind row
        int y2 = y1 + SLIDER_H + SP;
        Ui.card(g, rx, y2, rw, ROW_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Keybind", rx + 8, y2 + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);
        int bw = 72, bh = 16;
        int bx = rx + rw - 6 - bw;
        int by = y2 + (ROW_H - bh) / 2;
        boolean hover = Ui.in(mx, my, bx, by, bw, bh);
        Ui.rrect(g, bx, by, bw, bh, 6, listening ? Ui.ACCENT : (hover ? Ui.ROW_HOVER : Ui.ROW));
        String label = listening ? "..." : Ui.fit(font, keyName(), bw - 8);
        Ui.textCenter(g, font, label, bx + bw / 2, by + (bh - Ui.FONT_H) / 2 + 1,
                listening ? 0xFFFFFFFF : (keyName().equals("NONE") ? Ui.TEXT_DIM : Ui.TEXT));
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int y1 = ly + PAD;
        int y2 = y1 + SLIDER_H + SP;

        if (Ui.in(mx, my, rx, y1, rw, SLIDER_H)) {
            sliding = true;
            dragContent(mx, my);
            return true;
        }
        int bw = 72, bh = 16;
        int bx = rx + rw - 6 - bw;
        int by = y2 + (ROW_H - bh) / 2;
        if (Ui.in(mx, my, bx, by, bw, bh)) {
            listening = true;
            Ui.click(1.15f);
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
        cfg.freecamSpeed = Math.round((1f + v * 9f) * 10f) / 10f;     // 0.1 steps
    }

    @Override
    public void releaseContent() {
        if (sliding) {
            sliding = false;
            cfg.save();
        }
    }
}
