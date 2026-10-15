package com.example.nebula;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

/**
 * Marks the chunk of every other player your client can currently see (the same chunk as you or chunks near you)
 * with a rainbow layer at Y 45. It only uses players your game already knows about, so it works out to your
 * entity tracking distance. No settings.
 */
public final class LivePlayerDetectorModule implements NebulaModule {
    private final NebulaConfig cfg;

    /** Chunk keys with a player in them (filled by collect, read by the renderer). */
    public final long[] chunks = new long[64];
    public int chunkCount;

    public LivePlayerDetectorModule(NebulaConfig cfg) {
        this.cfg = cfg;
    }

    @Override public String name() { return "Live Player Detector"; }
    @Override public Category category() { return Category.RENDER; }
    @Override public boolean isEnabled() { return cfg.liveEnabled; }

    @Override
    public void setEnabled(boolean enabled) {
        cfg.liveEnabled = enabled;
        cfg.save();
    }

    public void collect(ClientLevel level, LocalPlayer self) {
        chunkCount = 0;
        for (AbstractClientPlayer p : level.players()) {
            if (p == self) continue;
            long k = ((p.getBlockX() >> 4) & 0xFFFFFFFFL) | ((long) (p.getBlockZ() >> 4) << 32);
            boolean duplicate = false;
            for (int i = 0; i < chunkCount; i++) {
                if (chunks[i] == k) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate && chunkCount < chunks.length) chunks[chunkCount++] = k;
        }
    }

    // no settings, so no dropdown
    @Override public int contentHeight() { return 0; }
    @Override public void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {}
    @Override public boolean clickContent(NebulaScreen screen, double mx, double my, int button) { return false; }
    @Override public void dragContent(double mx, double my) {}
    @Override public void releaseContent() {}
}
