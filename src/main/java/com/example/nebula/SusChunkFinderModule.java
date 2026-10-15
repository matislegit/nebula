package com.example.nebula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Looks for signs of player activity in the chunks around you and marks suspicious chunks with a purple layer
 * at Y 45 (drawn by {@link EspRenderer}).
 *
 * Signs (each can be switched off):
 *  - Amethyst:  fully grown amethyst clusters
 *  - Kelp:      fully grown kelp (age 25; naturally generated kelp tops are age 20-23, so 25 means it grew afterwards)
 *  - Cave Vines: fully grown cave vine heads (age 25)
 *  - Vines:     very long hanging vines
 *  - Bamboo:    fully grown (thick) bamboo
 *  - Rotated Deepslate: deepslate lying on its side (worldgen only makes it upright, so this is player placed)
 *
 * Every sign adds points to its chunk (with a cap per sign so one natural feature can't trigger it alone).
 * The sensitivity slider sets how many points make a chunk suspicious. Higher = more chunks, more false alarms.
 * Chunks that were flagged stay marked for the rest of the session, even after you leave them behind.
 */
public final class SusChunkFinderModule implements NebulaModule {
    public static final int PLANE_Y = 45;

    private static final String[] NAMES = {"Amethyst", "Kelp", "Cave Vines", "Vines", "Bamboo", "Rotated Deepslate"};
    private static final float[] WEIGHT = {0.5f, 1f, 1f, 1f, 0.25f, 6f};
    private static final float[] CAP = {10f, 10f, 10f, 10f, 10f, 20f};
    private static final int LONG_VINE = 7;
    private static final long SCAN_BUDGET_NANOS = 2_500_000L;

    private static final int PAD = 6;
    private static final int SLIDER_H = 30;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 4;
    private static final int SP = 6;

    private final NebulaConfig cfg;

    private final Map<Long, int[]> counts = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> announced = new HashSet<>();     // chunks we already sent a notification for
    private boolean dirty = true;
    private int cooldown;
    private int rebuildTimer;
    private int lastMask = -1;
    private Object lastLevel;

    /** Suspicious chunk keys (read by the renderer). */
    public long[] chunks = new long[256];
    public int chunkCount;

    // gui
    private int lx, ly, lw;
    private boolean sliding;
    private final float[] anim = new float[7];      // 0-5 = the signs, 6 = tracers

    public SusChunkFinderModule(NebulaConfig cfg) {
        this.cfg = cfg;
        for (int i = 0; i < 6; i++) anim[i] = type(i) ? 1f : 0f;
        anim[6] = cfg.susTracers ? 1f : 0f;
    }

    @Override public String name() { return "Sus Chunk Finder"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.susEnabled; }
    public boolean tracers() { return cfg.susTracers; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.susEnabled = enabled;
        cfg.save();
    }

    private boolean type(int i) {
        return switch (i) {
            case 0 -> cfg.susAmethyst;
            case 1 -> cfg.susKelp;
            case 2 -> cfg.susCaveVines;
            case 3 -> cfg.susVines;
            case 4 -> cfg.susBamboo;
            default -> cfg.susDeepslate;
        };
    }

    private void setType(int i, boolean v) {
        switch (i) {
            case 0 -> cfg.susAmethyst = v;
            case 1 -> cfg.susKelp = v;
            case 2 -> cfg.susCaveVines = v;
            case 3 -> cfg.susVines = v;
            case 4 -> cfg.susBamboo = v;
            default -> cfg.susDeepslate = v;
        }
    }

    private int mask() {
        int m = 0;
        for (int i = 0; i < 6; i++) if (type(i)) m |= 1 << i;
        return m;
    }

    // ------------------------------------------------------------ scoring
    private int threshold() {
        return Math.max(1, 21 - 2 * cfg.susSensitivity);
    }

    private static float score(int[] c) {
        float total = 0;
        for (int i = 0; i < 6; i++) total += Math.min(CAP[i], WEIGHT[i] * c[i]);
        return total;
    }

    private void rebuild() {
        int thr = threshold();
        int n = 0;
        boolean newFlag = false;
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            if (score(e.getValue()) >= thr) {
                long key = e.getKey();
                if (n == chunks.length) chunks = Arrays.copyOf(chunks, n * 2);
                chunks[n++] = key;
                if (announced.add(key)) {          // first time this chunk is flagged
                    int cx = (int) key;
                    int cz = (int) (key >>> 32);
                    NotificationsModule.push("Suspicious Chunk",
                            "Chunk " + cx + ", " + cz + "  (X " + (cx * 16) + ", Z " + (cz * 16) + ")",
                            0xA03CF0);
                    newFlag = true;
                }
            }
        }
        chunkCount = n;
        if (newFlag) NotificationsModule.playSusSound();      // one sound per batch, even if several chunks were found
    }

    // ------------------------------------------------------------ scanning
    private static long key(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** Which sign (0-5) this block state is, or -1. Only signs that are switched on are recognised. */
    private int classify(BlockState s, int mask) {
        Block b = s.getBlock();
        if ((mask & 2) != 0 && b == Blocks.KELP) {
            return s.getValue(BlockStateProperties.AGE_25) == 25 ? 1 : -1;
        }
        if ((mask & 4) != 0 && b == Blocks.CAVE_VINES) {
            return s.getValue(BlockStateProperties.AGE_25) == 25 ? 2 : -1;
        }
        if ((mask & 8) != 0 && b == Blocks.VINE) return 3;
        if ((mask & 16) != 0 && b == Blocks.BAMBOO) {
            return s.getValue(BlockStateProperties.AGE_1) == 1 ? 4 : -1;
        }
        if ((mask & 1) != 0 && b == Blocks.AMETHYST_CLUSTER) return 0;
        if ((mask & 32) != 0 && b == Blocks.DEEPSLATE) {
            return s.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y ? 5 : -1;
        }
        return -1;
    }

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != lastLevel) {
            counts.clear();
            queue.clear();
            announced.clear();
            chunkCount = 0;
            lastLevel = level;
            dirty = true;
        }
        if (!cfg.susEnabled || level == null || player == null) {
            queue.clear();
            chunkCount = 0;
            return;
        }
        int mask = mask();
        if (mask != lastMask) {
            lastMask = mask;
            dirty = true;
        }
        if (dirty) {
            counts.clear();
            queue.clear();
            cooldown = 0;
            dirty = false;
        }

        // scan around the player (the loaded area)
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int r = Math.max(2, Math.min(34, mc.options.getEffectiveRenderDistance() + 2));

        if (queue.isEmpty()) {
            if (cooldown > 0) {
                cooldown--;
            } else {
                List<int[]> order = new ArrayList<>();
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) order.add(new int[] {dx, dz});
                }
                order.sort((p, q) -> Integer.compare(p[0] * p[0] + p[1] * p[1], q[0] * q[0] + q[1] * q[1]));
                for (int[] o : order) queue.add(key(pcx + o[0], pcz + o[1]));
            }
        }

        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        int done = 0;
        while (!queue.isEmpty() && (done == 0 || System.nanoTime() < deadline)) {
            long k = queue.poll();
            int[] found = scanChunk(level, (int) k, (int) (k >>> 32), mask);
            if (found == null) counts.remove(k);
            else counts.put(k, found);
            done++;
        }
        if (done > 0 && queue.isEmpty()) cooldown = 100;      // full pass done: rescan every ~5 seconds

        if (--rebuildTimer <= 0) {
            rebuild();
            rebuildTimer = 10;
        }
    }

    private int[] scanChunk(ClientLevel level, int cx, int cz, int mask) {
        ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        Predicate<BlockState> interesting = s -> classify(s, mask) >= 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int[] result = null;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(interesting)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int t = classify(section.getBlockState(x, y, z), mask);
                        if (t < 0) continue;
                        if (t == 3 && !isLongVine(chunk, mp.set((cx << 4) + x, baseY + y, (cz << 4) + z))) continue;
                        if (result == null) result = new int[6];
                        result[t]++;
                    }
                }
            }
        }
        return result;
    }

    /** True if this vine is the top of a hanging column at least LONG_VINE blocks long (natural ones are short). */
    private static boolean isLongVine(ChunkAccess chunk, BlockPos.MutableBlockPos mp) {
        int x = mp.getX(), y = mp.getY(), z = mp.getZ();
        if (chunk.getBlockState(mp.set(x, y + 1, z)).is(Blocks.VINE)) return false;   // not the top
        int len = 1;
        while (len < LONG_VINE && chunk.getBlockState(mp.set(x, y - len, z)).is(Blocks.VINE)) len++;
        return len >= LONG_VINE;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + SLIDER_H + SP + 7 * ROW_H + 6 * ROW_GAP + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;

        // sensitivity slider
        int y1 = y + PAD;
        Ui.card(g, rx, y1, rw, SLIDER_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Sensitivity", rx + 8, y1 + 5, Ui.TEXT);
        Ui.textRight(g, font, cfg.susSensitivity + " / 10", rx + rw - 8, y1 + 5, Ui.TEXT_DIM);
        int tx = rx + 8;
        int tw = rw - 16;
        float frac = (cfg.susSensitivity - 1) / 9f;
        Ui.rrect(g, tx, y1 + 19, tw, 4, 2, Ui.TRACK_OFF);
        Ui.rrect(g, tx, y1 + 19, Math.max(4, Math.round(frac * tw)), 4, 2, Ui.ACCENT);
        int kx = tx + Math.round(frac * tw) - 5;
        Ui.rrect(g, kx - 1, y1 + 17, 12, 12, 6, 0x22000000);
        Ui.rrect(g, kx, y1 + 17, 10, 10, 5, Ui.KNOB);

        // row 0 = tracers, rows 1-6 = one toggle per sign
        int ty = y1 + SLIDER_H + SP;
        for (int r = 0; r < 7; r++) {
            int yy = ty + r * (ROW_H + ROW_GAP);
            boolean on = r == 0 ? cfg.susTracers : type(r - 1);
            int a = r == 0 ? 6 : r - 1;
            anim[a] = Ui.approach(anim[a], on ? 1f : 0f, 16f);
            boolean hover = Ui.in(mx, my, rx, yy, rw, ROW_H);
            Ui.card(g, rx, yy, rw, ROW_H, 6, hover ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
            Ui.text(g, font, r == 0 ? "Tracers" : NAMES[r - 1], rx + 8, yy + (ROW_H - Ui.FONT_H) / 2 + 1, Ui.TEXT);
            Ui.toggle(g, rx + rw - 8 - 28, yy + (ROW_H - 14) / 2, anim[a]);
        }
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int y1 = ly + PAD;
        if (Ui.in(mx, my, rx, y1, rw, SLIDER_H)) {
            sliding = true;
            dragContent(mx, my);
            return true;
        }
        int ty = y1 + SLIDER_H + SP;
        for (int r = 0; r < 7; r++) {
            int yy = ty + r * (ROW_H + ROW_GAP);
            if (Ui.in(mx, my, rx, yy, rw, ROW_H)) {
                boolean v;
                if (r == 0) {
                    cfg.susTracers = !cfg.susTracers;
                    v = cfg.susTracers;
                } else {
                    v = !type(r - 1);
                    setType(r - 1, v);
                }
                Ui.click(v ? 1.2f : 0.85f);
                cfg.save();
                return true;
            }
        }
        return false;
    }

    @Override
    public void dragContent(double mx, double my) {
        if (!sliding) return;
        int tx = lx + PAD + 8;
        int tw = lw - 2 * PAD - 16;
        float v = Ui.clamp01((float) ((mx - tx) / tw));
        int sens = 1 + Math.round(v * 9);
        if (sens != cfg.susSensitivity) {
            cfg.susSensitivity = sens;
            rebuildTimer = 0;       // update the marked chunks right away
        }
    }

    @Override
    public void releaseContent() {
        if (sliding) {
            sliding = false;
            cfg.save();
        }
    }
}
