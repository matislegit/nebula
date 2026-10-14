package com.example.nebula;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Nebula GUI: a white/gray panel of modules (left-click = toggle, right-click = dropdown),
 * plus a slide-out block picker with "Inactive" and "Active" tabs.
 */
public class NebulaScreen extends Screen {
    private static final int PANEL_W = 214;
    private static final int PICKER_W = 256;
    private static final int GAP = 10;
    private static final int PICKER_H_MAX = 340;
    private static final int HEADER = 40;
    private static final int ROW_H = 26;
    private static final int ROW_GAP = 5;
    private static final int FOOTER = 22;
    private static final int LIST_ROW_H = 24;

    private static final class Slot {
        final NebulaModule module;
        boolean expanded;
        float anim;      // dropdown open amount
        float on;        // toggle animation
        int rowY;
        int contentH;

        Slot(NebulaModule m) {
            module = m;
            on = m.isEnabled() ? 1f : 0f;
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private Slot dragging;

    private float openAnim;
    private float pickerAnim;
    private boolean pickerOpen;
    private long lastNanos;

    // layout (recomputed every frame)
    private int px, py, panelH, pickerX, pickerY, pickerH;

    // block picker
    private boolean activeTab;
    private float tabAnim;
    private String searchText = "";
    private EditBox search;
    private List<BlockCatalog.Entry> view = List.of();
    private boolean viewDirty = true;
    private float scroll, scrollTarget;

    // picker geometry captured while drawing (used by click handling)
    private int pkX, pkY;
    private int sbX, sbY, sbW, sbH;
    private int tabsX, tabsY, tabsW;
    private int listX, listTop, listBottom, listW;
    private int clearX, clearY, clearW, clearH;

    public NebulaScreen() {
        super(Component.literal("Nebula"));
        for (NebulaModule m : NebulaClient.MODULES) slots.add(new Slot(m));
    }

    public boolean isPickerOpen() { return pickerOpen; }

    public void togglePicker() {
        pickerOpen = !pickerOpen;
        viewDirty = true;
    }

    @Override
    protected void init() {
        search = new EditBox(this.font, 0, 0, PICKER_W - 44, 9, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(32);
        search.setTextColor(0xFF23272E);
        search.setValue(searchText);
        search.setResponder(s -> {
            searchText = s;
            viewDirty = true;
            scrollTarget = 0;
        });
        this.addWidget(search);
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // the world keeps running so you can watch the ESP while tweaking
    }

    @Override
    public void onClose() {
        NebulaClient.CONFIG.save();
        super.onClose();
    }

    // ================================================================ layout
    private void layout() {
        pickerH = Math.min(PICKER_H_MAX, this.height - 20);
        int totalW = PANEL_W + Math.round(pickerAnim * (GAP + PICKER_W));
        px = (this.width - totalW) / 2;
        py = (this.height - pickerH) / 2 + Math.round((1f - openAnim) * 16f);
        pickerX = px + PANEL_W + GAP;
        pickerY = py;

        int y = py + HEADER;
        for (Slot s : slots) {
            s.rowY = y;
            s.contentH = Math.round(s.anim * (s.module.contentHeight() + 4));
            y += ROW_H + s.contentH + ROW_GAP;
        }
        panelH = (y - py) + FOOTER - 2;
    }

    // ================================================================ render
    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = System.nanoTime();
        if (lastNanos == 0) lastNanos = now;
        Ui.dt = Math.min(0.05f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        openAnim = Ui.approach(openAnim, 1f, 14f);
        pickerAnim = Ui.approach(pickerAnim, pickerOpen ? 1f : 0f, 12f);
        tabAnim = Ui.approach(tabAnim, activeTab ? 1f : 0f, 16f);
        for (Slot s : slots) {
            s.anim = Ui.approach(s.anim, s.expanded ? 1f : 0f, 12f);
            s.on = Ui.approach(s.on, s.module.isEnabled() ? 1f : 0f, 16f);
        }
        float maxScroll = Math.max(0, view.size() * LIST_ROW_H - Math.max(0, listBottom - listTop));
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll = Ui.approach(scroll, scrollTarget, 18f);

        Ui.alpha = openAnim;
        g.fill(0, 0, this.width, this.height, ((int) (0x55 * openAnim)) << 24);

        layout();
        drawMainPanel(g, mx, my);
        if (pickerAnim > 0.01f) drawPicker(g, mx, my);

        Ui.alpha = 1f;
    }

    private void drawMainPanel(GuiGraphics g, int mx, int my) {
        Ui.shadow(g, px, py, PANEL_W, panelH, 12);
        Ui.card(g, px, py, PANEL_W, panelH, 12, Ui.PANEL, Ui.PANEL_BORDER);
        Ui.text(g, font, "NEBULA", px + 16, py + 15, Ui.TEXT);
        Ui.textRight(g, font, "v1.0", px + PANEL_W - 16, py + 15, Ui.TEXT_DIM);

        int rx = px + 10;
        int rw = PANEL_W - 20;
        for (Slot s : slots) {
            boolean hover = Ui.in(mx, my, rx, s.rowY, rw, ROW_H);
            int bg = Ui.lerp(hover ? Ui.ROW_HOVER : Ui.ROW, Ui.ROW_OPEN, s.anim);
            Ui.card(g, rx, s.rowY, rw, ROW_H, 8, bg, Ui.PANEL_BORDER);
            Ui.text(g, font, s.module.name(), rx + 12, s.rowY + (ROW_H - Ui.FONT_H) / 2 + 1,
                    Ui.lerp(Ui.TEXT_DIM, Ui.TEXT, s.on));
            Ui.toggle(g, rx + rw - 12 - 28, s.rowY + (ROW_H - 14) / 2, s.on);

            if (s.contentH > 1) {
                int cy = s.rowY + ROW_H + 2;
                g.enableScissor(rx, cy, rx + rw, cy + s.contentH);
                s.module.renderContent(this, g, font, rx, cy, rw, mx, my);
                g.disableScissor();
            }
        }

        Ui.textCenter(g, font, "LMB toggle  |  RMB settings", px + PANEL_W / 2,
                py + panelH - FOOTER + 2, Ui.TEXT_DIM);
    }

    private void refreshView() {
        BlockEspModule esp = NebulaClient.BLOCK_ESP;
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        List<BlockCatalog.Entry> out = new ArrayList<>();
        for (BlockCatalog.Entry e : BlockCatalog.all()) {
            if (esp.isSelected(e.block()) != activeTab) continue;
            if (!q.isEmpty() && !e.lowerName().contains(q) && !e.lowerId().contains(q)) continue;
            out.add(e);
        }
        view = out;
        viewDirty = false;
    }

    private void drawPicker(GuiGraphics g, int mx, int my) {
        if (viewDirty) refreshView();
        BlockEspModule esp = NebulaClient.BLOCK_ESP;

        float saved = Ui.alpha;
        Ui.alpha = saved * pickerAnim;

        int x = pickerX + Math.round((1f - pickerAnim) * -24f);
        int y = pickerY;
        pkX = x;
        pkY = y;

        Ui.shadow(g, x, y, PICKER_W, pickerH, 12);
        Ui.card(g, x, y, PICKER_W, pickerH, 12, Ui.PANEL, Ui.PANEL_BORDER);
        Ui.text(g, font, "BLOCKS", x + 16, y + 15, Ui.TEXT);
        boolean hClose = Ui.in(mx, my, x + PICKER_W - 34, y + 8, 24, 22);
        Ui.textRight(g, font, "x", x + PICKER_W - 16, y + 15, hClose ? Ui.TEXT : Ui.TEXT_DIM);

        // search field
        sbX = x + 12; sbY = y + 36; sbW = PICKER_W - 24; sbH = 22;
        Ui.card(g, sbX, sbY, sbW, sbH, 7, Ui.CARD, Ui.PANEL_BORDER);
        search.setPosition(sbX + 10, sbY + (sbH - 9) / 2 + 1);
        search.setWidth(sbW - 20);
        if (searchText.isEmpty() && !search.isFocused()) {
            Ui.text(g, font, "Search blocks...", sbX + 10, sbY + (sbH - Ui.FONT_H) / 2 + 1, Ui.TEXT_DIM);
        } else {
            search.render(g, mx, my, 0f);
        }

        // tabs
        tabsX = x + 12; tabsY = sbY + sbH + 8; tabsW = PICKER_W - 24;
        int tabH = 22;
        int tabW = tabsW / 2;
        int total = BlockCatalog.all().size();
        int activeCount = esp.selectedCount();
        Ui.rrect(g, tabsX, tabsY, tabsW, tabH, 8, Ui.ROW);
        int indX = tabsX + 2 + Math.round(tabAnim * (tabW - 1));
        Ui.rrect(g, indX, tabsY + 2, tabW - 3, tabH - 4, 6, Ui.ACCENT);
        Ui.textCenter(g, font, "Inactive (" + (total - activeCount) + ")", tabsX + tabW / 2,
                tabsY + (tabH - Ui.FONT_H) / 2 + 1, Ui.lerp(0xFFFFFFFF, Ui.TEXT_DIM, tabAnim));
        Ui.textCenter(g, font, "Active (" + activeCount + ")", tabsX + tabW + tabW / 2,
                tabsY + (tabH - Ui.FONT_H) / 2 + 1, Ui.lerp(Ui.TEXT_DIM, 0xFFFFFFFF, tabAnim));

        // list
        listX = x + 12;
        listW = PICKER_W - 24;
        listTop = tabsY + tabH + 8;
        listBottom = y + pickerH - 34;
        int listH = Math.max(0, listBottom - listTop);
        int rowW = listW - 8;
        int count = view.size();

        if (count == 0) {
            String msg = !searchText.isBlank() ? "No matches" : (activeTab ? "No blocks selected yet" : "Everything is selected");
            Ui.textCenter(g, font, msg, listX + listW / 2, listTop + 16, Ui.TEXT_DIM);
        } else {
            g.enableScissor(listX, listTop, listX + listW, listBottom);
            int first = Math.max(0, (int) (scroll / LIST_ROW_H));
            int last = Math.min(count - 1, (int) ((scroll + listH) / LIST_ROW_H));
            for (int i = first; i <= last; i++) {
                BlockCatalog.Entry e = view.get(i);
                int ry = listTop + i * LIST_ROW_H - Math.round(scroll);
                int rh = LIST_ROW_H - 3;
                boolean hover = Ui.in(mx, my, listX, ry, rowW, rh) && my >= listTop && my < listBottom;
                Ui.rrect(g, listX, ry, rowW, rh, 7, hover ? Ui.ROW_HOVER : Ui.ROW);

                if (pickerAnim > 0.9f && !e.stack().isEmpty()) {
                    g.renderItem(e.stack(), listX + 6, ry + (rh - 16) / 2);
                } else {
                    Ui.rrect(g, listX + 9, ry + (rh - 12) / 2, 12, 12, 4, e.color());
                }
                Ui.text(g, font, Ui.fit(font, e.name(), rowW - 28 - 34), listX + 28, ry + (rh - Ui.FONT_H) / 2 + 1, Ui.TEXT);

                if (activeTab) {
                    Ui.rrect(g, listX + rowW - 32, ry + (rh - 8) / 2, 8, 8, 4, e.color());   // its ESP colour
                    Ui.textRight(g, font, "-", listX + rowW - 9, ry + (rh - Ui.FONT_H) / 2 + 1, Ui.TEXT_DIM);
                } else {
                    Ui.textRight(g, font, "+", listX + rowW - 9, ry + (rh - Ui.FONT_H) / 2 + 1, Ui.TEXT_DIM);
                }
            }
            g.disableScissor();

            float maxScroll = Math.max(0, count * LIST_ROW_H - listH);
            if (maxScroll > 0) {
                int thumbH = Math.max(22, (int) (listH * (listH / (float) (count * LIST_ROW_H))));
                int thumbY = listTop + Math.round((scroll / maxScroll) * (listH - thumbH));
                Ui.rrect(g, listX + listW - 4, thumbY, 3, thumbH, 1, 0xFFC3C8CF);
            }
        }

        // bottom bar
        int by = y + pickerH - 26;
        Ui.text(g, font, "Click a block to move it", x + 16, by + 5, Ui.TEXT_DIM);
        if (activeTab && activeCount > 0) {
            clearW = 64; clearH = 18; clearX = x + PICKER_W - 14 - clearW; clearY = by + 1;
            boolean hc = Ui.in(mx, my, clearX, clearY, clearW, clearH);
            Ui.card(g, clearX, clearY, clearW, clearH, 6, hc ? Ui.CARD_HOVER : Ui.CARD, Ui.PANEL_BORDER);
            Ui.textCenter(g, font, "Clear all", clearX + clearW / 2, clearY + 5, Ui.TEXT);
        } else {
            clearW = 0; clearH = 0;
        }

        Ui.alpha = saved;
    }

    // ================================================================ input
    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean typing = search != null && search.isFocused();
        if (!typing && NebulaClient.OPEN_GUI.matches(event)) {
            onClose();      // same key opens and closes the GUI
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();
        layout();

        boolean pickerVisible = pickerAnim > 0.5f;
        boolean inSearch = pickerVisible && Ui.in(mx, my, sbX, sbY, sbW, sbH);
        if (!inSearch) this.setFocused(null);

        if (pickerVisible) {
            if (Ui.in(mx, my, pkX + PICKER_W - 34, pkY + 8, 24, 22)) {
                pickerOpen = false;
                Ui.click(0.9f);
                return true;
            }
            if (inSearch) {
                this.setFocused(search);
                search.mouseClicked(event, doubleClick);
                return true;
            }
            if (Ui.in(mx, my, tabsX, tabsY, tabsW, 22)) {
                boolean newTab = mx >= tabsX + tabsW / 2;
                if (newTab != activeTab) {
                    activeTab = newTab;
                    viewDirty = true;
                    scroll = 0;
                    scrollTarget = 0;
                    Ui.click(1.05f);
                }
                return true;
            }
            if (clearW > 0 && Ui.in(mx, my, clearX, clearY, clearW, clearH)) {
                NebulaClient.BLOCK_ESP.clearSelection();
                viewDirty = true;
                Ui.click(0.8f);
                return true;
            }
            if (Ui.in(mx, my, listX, listTop, listW, listBottom - listTop)) {
                double rel = my - listTop + scroll;
                int idx = (int) (rel / LIST_ROW_H);
                if (idx >= 0 && idx < view.size() && (rel % LIST_ROW_H) < LIST_ROW_H - 3) {
                    BlockCatalog.Entry e = view.get(idx);
                    NebulaClient.BLOCK_ESP.toggleBlock(e.block());
                    viewDirty = true;
                    Ui.click(activeTab ? 0.85f : 1.2f);
                }
                return true;
            }
            if (Ui.in(mx, my, pkX, pkY, PICKER_W, pickerH)) return true;
        }

        int rx = px + 10;
        int rw = PANEL_W - 20;
        for (Slot s : slots) {
            if (Ui.in(mx, my, rx, s.rowY, rw, ROW_H)) {
                if (btn == 0) {
                    s.module.setEnabled(!s.module.isEnabled());
                    Ui.click(s.module.isEnabled() ? 1.2f : 0.85f);
                } else if (btn == 1) {
                    s.expanded = !s.expanded;
                    Ui.click(s.expanded ? 1.05f : 0.9f);
                }
                return true;
            }
            if (s.anim > 0.9f && Ui.in(mx, my, rx, s.rowY + ROW_H + 2, rw, s.contentH)) {
                if (s.module.clickContent(this, mx, my, btn)) dragging = s;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.module.dragContent(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging.module.releaseContent();
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (pickerAnim > 0.5f && Ui.in(mx, my, pkX, pkY, PICKER_W, pickerH)) {
            scrollTarget -= (float) (scrollY * LIST_ROW_H * 2);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
