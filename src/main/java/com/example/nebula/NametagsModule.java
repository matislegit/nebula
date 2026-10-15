package com.example.nebula;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;

/**
 * Replaces the vanilla name tag of other players with a tag showing (each part can be switched off):
 * mainhand, armor, offhand, health, distance and the number of totems they are holding.
 * The tag is drawn on the HUD at the player's projected screen position, so it is visible through walls.
 *
 * Note: your game only receives what other players hold or wear, not their inventory, so the totem count is
 * the totems in their hands.
 */
public final class NametagsModule implements NebulaModule {
    private static final String[] NAMES = {"Armor", "Offhand", "Mainhand", "Health", "Distance", "Totems"};
    private static final int MAX_TAGS = 24;

    private static final int PAD = 6;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 4;

    private final NebulaConfig cfg;
    private final float[] anim = new float[6];
    private int lx, ly, lw;

    // per-frame scratch data
    private final List<AbstractClientPlayer> tmp = new ArrayList<>();
    private final List<ItemStack> items = new ArrayList<>();
    private final Vector3f v = new Vector3f();
    private final float[] screen = new float[2];
    private float fov = 70f;
    private long fovNanos;
    private boolean fovInit;

    public NametagsModule(NebulaConfig cfg) {
        this.cfg = cfg;
        for (int i = 0; i < 6; i++) anim[i] = opt(i) ? 1f : 0f;
    }

    @Override public String name() { return "Nametags"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.nametagsEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.nametagsEnabled = enabled;
        cfg.save();
    }

    private boolean opt(int i) {
        return switch (i) {
            case 0 -> cfg.ntArmor;
            case 1 -> cfg.ntOffhand;
            case 2 -> cfg.ntMainhand;
            case 3 -> cfg.ntHealth;
            case 4 -> cfg.ntDistance;
            default -> cfg.ntTotems;
        };
    }

    private void setOpt(int i, boolean val) {
        switch (i) {
            case 0 -> cfg.ntArmor = val;
            case 1 -> cfg.ntOffhand = val;
            case 2 -> cfg.ntMainhand = val;
            case 3 -> cfg.ntHealth = val;
            case 4 -> cfg.ntDistance = val;
            default -> cfg.ntTotems = val;
        }
    }

    // ------------------------------------------------------------ projection (world -> screen)
    /** Same field of view the game is using right now (base setting x sprint/speed effects, smoothed like vanilla). */
    private float currentFov(Minecraft mc, LocalPlayer self) {
        float base = mc.options.fov().get();
        boolean first = mc.options.getCameraType().isFirstPerson();
        float target = base * self.getFieldOfViewModifier(first, mc.options.fovEffectScale().get().floatValue());
        long now = System.nanoTime();
        float dt = fovNanos == 0 ? 0.016f : Math.min(0.1f, (now - fovNanos) / 1_000_000_000f);
        fovNanos = now;
        if (!fovInit || Math.abs(target - fov) > 40f) {
            fov = target;
            fovInit = true;
        } else {
            fov += (target - fov) * (1f - (float) Math.exp(-14f * dt));
        }
        return fov;
    }

    private boolean project(double wx, double wy, double wz, int guiW, int guiH, float fovDeg) {
        v.set((float) (wx - EspRenderer.camX), (float) (wy - EspRenderer.camY), (float) (wz - EspRenderer.camZ));
        EspRenderer.camRot.transformInverse(v);          // world space -> camera space
        if (v.z > -0.05f) return false;                  // behind the camera
        double halfTan = Math.tan(Math.toRadians(fovDeg) * 0.5);
        double aspect = guiW / (double) guiH;
        double ndcX = (v.x / -v.z) / (halfTan * aspect);
        double ndcY = (v.y / -v.z) / halfTan;
        screen[0] = (float) ((ndcX * 0.5 + 0.5) * guiW);
        screen[1] = (float) ((1.0 - (ndcY * 0.5 + 0.5)) * guiH);
        return true;
    }

    // ------------------------------------------------------------ drawing (HUD)
    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        NametagsModule m = NebulaClient.NAMETAGS;
        if (m == null || !m.isEnabled() || !EspRenderer.camValid) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null || self == null) return;

        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        float fovDeg = m.currentFov(mc, self);
        int gw = g.guiWidth();
        int gh = g.guiHeight();

        List<AbstractClientPlayer> list = m.tmp;
        list.clear();
        for (AbstractClientPlayer p : level.players()) {
            if (p != self && !p.isRemoved()) list.add(p);
        }
        if (list.isEmpty()) return;
        list.sort(Comparator.comparingDouble(self::distanceToSqr));            // nearest first
        int count = Math.min(list.size(), MAX_TAGS);

        float savedAlpha = Ui.alpha;
        Ui.alpha = 1f;
        for (int i = count - 1; i >= 0; i--) {                                  // draw far ones first, near ones on top
            AbstractClientPlayer p = list.get(i);
            double x = Mth.lerp(partial, p.xo, p.getX());
            double y = Mth.lerp(partial, p.yo, p.getY()) + p.getBbHeight() + 0.35;
            double z = Mth.lerp(partial, p.zo, p.getZ());
            if (!m.project(x, y, z, gw, gh, fovDeg)) continue;
            float sx = m.screen[0];
            float sy = m.screen[1];
            if (sx < -250 || sx > gw + 250 || sy < -120 || sy > gh + 120) continue;
            m.drawTag(g, mc.font, self, p, Math.round(sx), Math.round(sy));
        }
        Ui.alpha = savedAlpha;
    }

    private int hpColor(float hp, float max) {
        float ratio = max <= 0 ? 1f : hp / max;
        if (ratio > 0.66f) return 0xFF2E9E4F;
        if (ratio > 0.33f) return 0xFFC99A1B;
        return 0xFFD64545;
    }

    private void drawTag(GuiGraphics g, Font font, LocalPlayer self, AbstractClientPlayer p, int sx, int sy) {
        // what to show above the text
        items.clear();
        if (cfg.ntMainhand && !p.getMainHandItem().isEmpty()) items.add(p.getMainHandItem());
        if (cfg.ntArmor) {
            ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
            ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
            ItemStack legs = p.getItemBySlot(EquipmentSlot.LEGS);
            ItemStack feet = p.getItemBySlot(EquipmentSlot.FEET);
            if (!head.isEmpty()) items.add(head);
            if (!chest.isEmpty()) items.add(chest);
            if (!legs.isEmpty()) items.add(legs);
            if (!feet.isEmpty()) items.add(feet);
        }
        if (cfg.ntOffhand && !p.getOffhandItem().isEmpty()) items.add(p.getOffhandItem());

        // text pieces: name, then each enabled stat
        String[] parts = new String[4];
        int[] colors = new int[4];
        int np = 0;
        parts[np] = p.getName().getString();
        colors[np++] = Ui.TEXT;
        if (cfg.ntHealth) {
            float hp = p.getHealth() + p.getAbsorptionAmount();
            parts[np] = (int) Math.ceil(hp) + " HP";
            colors[np++] = hpColor(hp, p.getMaxHealth());
        }
        if (cfg.ntDistance) {
            parts[np] = Math.round(self.distanceTo(p)) + "m";
            colors[np++] = 0xFF6B7280;
        }
        if (cfg.ntTotems) {
            int totems = 0;
            if (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) totems += p.getMainHandItem().getCount();
            if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) totems += p.getOffhandItem().getCount();
            parts[np] = "T" + totems;
            colors[np++] = totems > 0 ? 0xFFB45309 : 0xFFD64545;
        }

        int textW = 0;
        int[] widths = new int[np];
        for (int i = 0; i < np; i++) {
            widths[i] = Ui.width(font, parts[i]);
            textW += widths[i] + (i > 0 ? 5 : 0);
        }
        int itemsW = items.isEmpty() ? 0 : items.size() * 18 - 2;
        int w = Math.max(itemsW, textW) + 12;
        int h = 15 + (items.isEmpty() ? 0 : 20);
        int x = sx - w / 2;
        int y = sy - h;

        Ui.card(g, x, y, w, h, 5, 0xE6FFFFFF, 0x66AEB4BC);

        int ix = sx - itemsW / 2;
        for (ItemStack it : items) {
            g.renderItem(it, ix, y + 3);
            g.renderItemDecorations(font, it, ix, y + 3);        // durability bar / stack size
            ix += 18;
        }
        int tx = sx - textW / 2;
        int ty = y + h - Ui.FONT_H - 4;
        for (int i = 0; i < np; i++) {
            Ui.text(g, font, parts[i], tx, ty, colors[i]);
            tx += widths[i] + 5;
        }
    }

    // ------------------------------------------------------------ gui dropdown (one toggle per part)
    @Override
    public int contentHeight() {
        return PAD + 6 * ROW_H + 5 * ROW_GAP + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;
        for (int i = 0; i < 6; i++) {
            int yy = y + PAD + i * (ROW_H + ROW_GAP);
            anim[i] = Ui.approach(anim[i], opt(i) ? 1f : 0f, 16f);
            boolean hover = Ui.in(mx, my, rx, yy, rw, ROW_H);
            Ui.card(g, rx, yy, rw, ROW_H, 6, hover ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
            Ui.text(g, font, NAMES[i], rx + 8, yy + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);
            Ui.toggle(g, rx + rw - 8 - 28, yy + (ROW_H - 14) / 2, anim[i]);
        }
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        for (int i = 0; i < 6; i++) {
            int yy = ly + PAD + i * (ROW_H + ROW_GAP);
            if (Ui.in(mx, my, rx, yy, rw, ROW_H)) {
                boolean val = !opt(i);
                setOpt(i, val);
                Ui.click(val ? 1.2f : 0.85f);
                cfg.save();
                return true;
            }
        }
        return false;
    }

    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
