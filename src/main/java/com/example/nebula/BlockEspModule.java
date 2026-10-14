package com.example.nebula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Block ESP: finds the selected blocks around you (a few chunks per tick, nearest first)
 * and hands them to {@link EspRenderer}, which draws them through walls.
 */
public final class BlockEspModule implements NebulaModule {
    public static final int MAX_BLOCKS = 2000;          // most blocks drawn per frame (nearest win)
    private static final int PER_CHUNK_CAP = 1536;
    private static final int CHUNKS_PER_TICK = 4;

    private static final int PAD = 6;
    private static final int ROW1_H = 20;
    private static final int SLIDER_H = 30;
    private static final int ROW3_H = 20;
    private static final int SP = 6;

    private final NebulaConfig cfg;
    private final Set<Block> selected = new HashSet<>();
    private final Map<Long, long[]> hits = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private boolean dirty = true;
    private Object lastLevel;

    // collected for rendering
    private long[] cPos = new long[4096];
    private float[] cD2 = new float[4096];
    private float[] tmp = new float[4096];
    public final long[] outPos = new long[MAX_BLOCKS];
    public final int[] outColor = new int[MAX_BLOCKS];

    // gui state
    private int lx, ly, lw;
    private boolean sliding;
    private float tracerAnim;

    public BlockEspModule(NebulaConfig cfg) {
        this.cfg = cfg;
        tracerAnim = cfg.blockEspTracers ? 1f : 0f;
        for (String s : cfg.blockEspBlocks) {
            Identifier id = Identifier.tryParse(s);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                selected.add(BuiltInRegistries.BLOCK.getValue(id));
            }
        }
    }

    // ------------------------------------------------------------ settings
    @Override public String name() { return "Block ESP"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.blockEspEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.blockEspEnabled = enabled;
        dirty = true;
        cfg.save();
    }

    public float opacity() { return cfg.blockEspOpacity; }
    public boolean tracers() { return cfg.blockEspTracers; }
    public boolean isSelected(Block b) { return selected.contains(b); }
    public int selectedCount() { return selected.size(); }

    public void toggleBlock(Block b) {
        if (!selected.remove(b)) selected.add(b);
        persistSelection();
    }

    public void clearSelection() {
        selected.clear();
        persistSelection();
    }

    private void persistSelection() {
        List<String> ids = new ArrayList<>();
        for (Block b : selected) ids.add(BuiltInRegistries.BLOCK.getKey(b).toString());
        Collections.sort(ids);
        cfg.blockEspBlocks = ids;
        dirty = true;
        cfg.save();
    }

    // ------------------------------------------------------------ scanning (client tick)
    private static long key(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != lastLevel) {
            hits.clear();
            queue.clear();
            lastLevel = level;
            dirty = true;
        }
        if (!cfg.blockEspEnabled || level == null || player == null || selected.isEmpty()) {
            if (!hits.isEmpty()) hits.clear();
            queue.clear();
            return;
        }
        if (dirty) {
            hits.clear();
            queue.clear();
            dirty = false;
        }

        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int r = cfg.blockEspRadiusChunks;

        if (queue.isEmpty()) {
            hits.keySet().removeIf(k -> Math.abs((int) (long) k - pcx) > r || Math.abs((int) (k >>> 32) - pcz) > r);
            List<int[]> order = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) order.add(new int[] {dx, dz});
            }
            order.sort((p, q) -> Integer.compare(p[0] * p[0] + p[1] * p[1], q[0] * q[0] + q[1] * q[1]));
            for (int[] o : order) queue.add(key(pcx + o[0], pcz + o[1]));
        }

        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
            long k = queue.poll();
            long[] found = scanChunk(level, (int) k, (int) (k >>> 32));
            if (found == null || found.length == 0) hits.remove(k);
            else hits.put(k, found);
        }
    }

    private long[] scanChunk(ClientLevel level, int cx, int cz) {
        ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        Predicate<BlockState> wanted = s -> selected.contains(s.getBlock());
        LevelChunkSection[] sections = chunk.getSections();
        LongArrayList out = null;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(wanted)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (selected.contains(section.getBlockState(x, y, z).getBlock())) {
                            if (out == null) out = new LongArrayList();
                            out.add(BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z));
                            if (out.size() >= PER_CHUNK_CAP) return out.toLongArray();
                        }
                    }
                }
            }
        }
        return out == null ? null : out.toLongArray();
    }

    // ------------------------------------------------------------ collect (render thread)
    /** Fills outPos / outColor with the nearest blocks. @return how many. */
    public int collect(ClientLevel level, double cx, double cy, double cz) {
        if (selected.isEmpty() || hits.isEmpty()) return 0;
        double range = cfg.blockEspRadiusChunks * 16.0 + 8.0;
        double r2 = range * range;

        int n = 0;
        for (long[] arr : hits.values()) {
            for (long p : arr) {
                double dx = BlockPos.getX(p) + 0.5 - cx;
                double dy = BlockPos.getY(p) + 0.5 - cy;
                double dz = BlockPos.getZ(p) + 0.5 - cz;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2) continue;
                if (n == cPos.length) {
                    cPos = Arrays.copyOf(cPos, n * 2);
                    cD2 = Arrays.copyOf(cD2, n * 2);
                    tmp = new float[n * 2];
                }
                cPos[n] = p;
                cD2[n] = (float) d2;
                n++;
            }
        }

        float cutoff = Float.MAX_VALUE;
        if (n > MAX_BLOCKS) {
            System.arraycopy(cD2, 0, tmp, 0, n);
            Arrays.sort(tmp, 0, n);
            cutoff = tmp[MAX_BLOCKS - 1];
        }

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int i = 0; i < n && count < MAX_BLOCKS; i++) {
            if (cD2[i] > cutoff) continue;
            mut.set(cPos[i]);
            Block b = level.getBlockState(mut).getBlock();
            if (!selected.contains(b)) continue;     // mined / changed since the scan
            outPos[count] = cPos[i];
            outColor[count] = BlockCatalog.color(b);
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------ gui dropdown
    @Override
    public int contentHeight() {
        return PAD + ROW1_H + SP + SLIDER_H + SP + ROW3_H + PAD;
    }

    @Override
    public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
        lx = x; ly = y; lw = w;
        tracerAnim = Ui.approach(tracerAnim, cfg.blockEspTracers ? 1f : 0f, 16f);

        Ui.rrect(g, x, y, w, contentHeight(), 8, Ui.CONTENT);
        int rx = x + PAD;
        int rw = w - 2 * PAD;

        // Row 1: blocks selected -> opens the block picker
        int y1 = y + PAD;
        boolean h1 = Ui.in(mx, my, rx, y1, rw, ROW1_H);
        Ui.card(g, rx, y1, rw, ROW1_H, 6, h1 ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Blocks Selected: " + selected.size(), rx + 8, y1 + 6, Ui.TEXT);
        Ui.textRight(g, font, screen.isPickerOpen() ? "<" : ">", rx + rw - 8, y1 + 6, Ui.TEXT_DIM);

        // Row 2: opacity slider
        int y2 = y1 + ROW1_H + SP;
        Ui.card(g, rx, y2, rw, SLIDER_H, 6, Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Opacity", rx + 8, y2 + 5, Ui.TEXT);
        Ui.textRight(g, font, Math.round(cfg.blockEspOpacity * 100) + "%", rx + rw - 8, y2 + 5, Ui.TEXT_DIM);
        int tx = rx + 8;
        int tw = rw - 16;
        float frac = (cfg.blockEspOpacity - 0.05f) / 0.95f;
        Ui.rrect(g, tx, y2 + 19, tw, 4, 2, Ui.TRACK_OFF);
        Ui.rrect(g, tx, y2 + 19, Math.max(4, Math.round(frac * tw)), 4, 2, Ui.ACCENT);
        int kx = tx + Math.round(frac * tw) - 5;
        Ui.rrect(g, kx - 1, y2 + 17, 12, 12, 6, 0x22000000);
        Ui.rrect(g, kx, y2 + 17, 10, 10, 5, Ui.KNOB);

        // Row 3: tracers toggle
        int y3 = y2 + SLIDER_H + SP;
        boolean h3 = Ui.in(mx, my, rx, y3, rw, ROW3_H);
        Ui.card(g, rx, y3, rw, ROW3_H, 6, h3 ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
        Ui.text(g, font, "Tracers", rx + 8, y3 + 6, Ui.TEXT);
        Ui.toggle(g, rx + rw - 8 - 28, y3 + 3, tracerAnim);
    }

    @Override
    public boolean clickContent(NebulaScreen screen, double mx, double my, int button) {
        if (button != 0) return false;
        int rx = lx + PAD;
        int rw = lw - 2 * PAD;
        int y1 = ly + PAD;
        int y2 = y1 + ROW1_H + SP;
        int y3 = y2 + SLIDER_H + SP;

        if (Ui.in(mx, my, rx, y1, rw, ROW1_H)) {
            screen.togglePicker();
            Ui.click(screen.isPickerOpen() ? 1.1f : 0.9f);
            return true;
        }
        if (Ui.in(mx, my, rx, y2, rw, SLIDER_H)) {
            sliding = true;
            dragContent(mx, my);
            return true;
        }
        if (Ui.in(mx, my, rx, y3, rw, ROW3_H)) {
            cfg.blockEspTracers = !cfg.blockEspTracers;
            Ui.click(cfg.blockEspTracers ? 1.2f : 0.85f);
            cfg.save();
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
        cfg.blockEspOpacity = 0.05f + v * 0.95f;
    }

    @Override
    public void releaseContent() {
        if (sliding) {
            sliding = false;
            cfg.save();
        }
    }
}
