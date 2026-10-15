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
    private static final int SIDE_W = 84;
    private static final int SIDE_GAP = 8;
    private static final int CAT_H = 24;
    private static final int CAT_GAP = 4;
    private static final int PANEL_W = 280;
    private static final int PICKER_W = 240;
    private static final int GAP = 10;
    private static final int PICKER_H_MAX = 340;
    private static final int HEADER = 40;
    private static final int PAD = 10;
    private static final int CARD_H = 46;
    private static final int CARD_GAP = 6;
    private static final int ROW_GAP = 6;
    private static final int FOOTER = 22;
    private static final int LIST_ROW_H = 24;

    private static final class Slot {
        final NebulaModule module;
        boolean expanded;
        float anim;      // dropdown open amount
        float on;        // toggle animation
        int cardX, cardY, cardW;      // the module's card (absolute screen position)
        int contentY, contentH;       // its dropdown area, shown full width under the row
        int relCardY, relContentY;    // positions inside the scroll area, before scrolling

        Slot(NebulaModule m) {
            module = m;
            on = m.isEnabled() ? 1f : 0f;
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private Slot dragging;

    private Category category = Category.ALL;
    private float catPos;       // animated sidebar indicator position (index)
    private float modFade = 1f; // fade-in when switching category

    private float openAnim;
    private float pickerAnim;
    private boolean pickerOpen;
    private long lastNanos;

    // layout (recomputed every frame)
    private int sx, px, py, panelH, pickerX, pickerY, pickerH;

    // the scrollable module area
    private final List<Slot> vis = new ArrayList<>();
    private int viewTop, viewH, totalContent;
    private float mScroll, mScrollTarget, maxMScroll;

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
        for (Slot s : slots) s.module.initWidgets(this, this.font);
    }

    /** Lets a module register a text box so it receives keyboard input. */
    public void addModuleWidget(EditBox box) {
        this.addWidget(box);
    }

    /** Gives keyboard focus to a module's text box. */
    public void focusWidget(EditBox box) {
        this.setFocused(box);
    }

    @Override
    public void removed() {
        for (Slot s : slots) s.module.onScreenClosed();
        NebulaClient.CONFIG.save();
        super.removed();
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
    private boolean visible(Slot s) {
        return category == Category.ALL || s.module.category() == category;
    }

    private void layout() {
        pickerH = Math.min(PICKER_H_MAX, this.height - 20);
        int totalW = SIDE_W + SIDE_GAP + PANEL_W + Math.round(pickerAnim * (GAP + PICKER_W));
        sx = (this.width - totalW) / 2;
        px = sx + SIDE_W + SIDE_GAP;
        py = (this.height - pickerH) / 2 + Math.round((1f - openAnim) * 16f);
        pickerX = px + PANEL_W + GAP;
        pickerY = py;

        // two modules per row; a module's dropdown opens full width under its row and pushes the rest down
        vis.clear();
        for (Slot s : slots) if (visible(s)) vis.add(s);
        int innerW = PANEL_W - 2 * PAD;
        int cardW = (innerW - CARD_GAP) / 2;
        int y = 0;
        for (int i = 0; i < vis.size(); i += 2) {
            Slot a = vis.get(i);
            Slot b = i + 1 < vis.size() ? vis.get(i + 1) : null;
            a.cardX = px + PAD;
            a.cardW = cardW;
            a.relCardY = y;
            if (b != null) {
                b.cardX = px + PAD + cardW + CARD_GAP;
                b.cardW = cardW;
                b.relCardY = y;
            }
            y += CARD_H + ROW_GAP;
            y = placeContent(a, y);
            if (b != null) y = placeContent(b, y);
        }
        totalContent = vis.isEmpty() ? 34 : Math.max(0, y - ROW_GAP);

        int maxView = Math.max(90, pickerH - HEADER - FOOTER);
        viewH = Math.min(totalContent + 2, maxView);
        panelH = HEADER + viewH + FOOTER;
        maxMScroll = Math.max(0, totalContent + 2 - viewH);
        mScrollTarget = Math.max(0, Math.min(maxMScroll, mScrollTarget));
        mScroll = Math.max(0, Math.min(maxMScroll, mScroll));

        viewTop = py + HEADER;
        int off = viewTop - Math.round(mScroll);
        for (Slot s : vis) {
            s.cardY = off + s.relCardY;
            s.contentY = off + s.relContentY;
        }
    }

    private int placeContent(Slot s, int y) {
        s.contentH = Math.round(s.anim * (s.module.contentHeight() + 4));
        s.relContentY = y;
        return s.contentH > 0 ? y + s.contentH + ROW_GAP : y;
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
        catPos = Ui.approach(catPos, category.ordinal(), 16f);
        modFade = Ui.approach(modFade, 1f, 14f);
        for (Slot s : slots) {
            s.anim = Ui.approach(s.anim, s.expanded ? 1f : 0f, 12f);
            s.on = Ui.approach(s.on, s.module.isEnabled() ? 1f : 0f, 16f);
        }
        float maxScroll = Math.max(0, view.size() * LIST_ROW_H - Math.max(0, listBottom - listTop));
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll = Ui.approach(scroll, scrollTarget, 18f);
        mScroll = Ui.approach(mScroll, mScrollTarget, 18f);

        Ui.alpha = openAnim;
        g.fill(0, 0, this.width, this.height, ((int) (0x55 * openAnim)) << 24);

        layout();
        drawSidebar(g, mx, my);
        drawMainPanel(g, mx, my);
        if (pickerAnim > 0.01f) drawPicker(g, mx, my);

        Ui.alpha = 1f;
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        Category[] cats = Category.values();
        int top = py + 12;
        int sideH = 12 + cats.length * (CAT_H + CAT_GAP) - CAT_GAP + 12;
        Ui.shadow(g, sx, py, SIDE_W, sideH, 12);
        Ui.card(g, sx, py, SIDE_W, sideH, 12, Ui.PANEL, Ui.PANEL_BORDER);

        int bx = sx + 8;
        int bw = SIDE_W - 16;
        for (int i = 0; i < cats.length; i++) {
            int by = top + i * (CAT_H + CAT_GAP);
            if (i != category.ordinal() && Ui.in(mx, my, bx, by, bw, CAT_H)) {
                Ui.rrect(g, bx, by, bw, CAT_H, 8, Ui.ROW);
            }
        }
        Ui.rrect(g, bx, top + Math.round(catPos * (CAT_H + CAT_GAP)), bw, CAT_H, 8, Ui.ACCENT);
        for (int i = 0; i < cats.length; i++) {
            int by = top + i * (CAT_H + CAT_GAP);
            float t = 1f - Math.min(1f, Math.abs(catPos - i));
            Ui.textCenter(g, font, cats[i].label, bx + bw / 2, by + (CAT_H - Ui.FONT_H) / 2 + 1,
                    Ui.lerp(Ui.TEXT_DIM, 0xFFFFFFFF, t));
        }
    }

    private void drawMainPanel(GuiGraphics g, int mx, int my) {
        Ui.shadow(g, px, py, PANEL_W, panelH, 12);
        Ui.card(g, px, py, PANEL_W, panelH, 12, Ui.PANEL, Ui.PANEL_BORDER);
        Ui.text(g, font, "NEBULA", px + 16, py + 15, Ui.TEXT);
        Ui.textRight(g, font, "v1.0", px + PANEL_W - 16, py + 15, Ui.TEXT_DIM);

        int innerX = px + PAD;
        int innerW = PANEL_W - 2 * PAD;
        float panelAlpha = Ui.alpha;
        Ui.alpha = panelAlpha * modFade;

        if (vis.isEmpty()) {
            Ui.textCenter(g, font, "No modules here yet", px + PANEL_W / 2, viewTop + 10, Ui.TEXT_DIM);
        }

        // everything inside the scroll box is clipped to it
        boolean mouseInView = my >= viewTop && my < viewTop + viewH;
        g.enableScissor(px + 4, viewTop, px + PANEL_W - 4, viewTop + viewH);
        for (Slot s : vis) {
            boolean canExpand = s.module.contentHeight() > 0;
            boolean hover = mouseInView && Ui.in(mx, my, s.cardX, s.cardY, s.cardW, CARD_H);
            int bg = Ui.lerp(hover ? Ui.ROW_HOVER : Ui.ROW, Ui.ROW_OPEN, s.anim);
            Ui.card(g, s.cardX, s.cardY, s.cardW, CARD_H, 8, bg, Ui.PANEL_BORDER);

            drawCardName(g, s.module.name(), s.cardX + 10, s.cardY + 7, s.cardW - 20,
                    Ui.lerp(Ui.TEXT_DIM, Ui.TEXT, s.on));
            Ui.toggle(g, s.cardX + 10, s.cardY + CARD_H - 14 - 6, s.on);
            if (canExpand) {   // three dots = "has settings" (turn dark while open)
                int dotColor = Ui.lerp(Ui.TRACK_OFF, Ui.ACCENT, s.anim);
                for (int k = 0; k < 3; k++) {
                    Ui.rrect(g, s.cardX + s.cardW - 24 + k * 6, s.cardY + CARD_H - 14, 3, 3, 1, dotColor);
                }
            }

            if (s.contentH > 1) {
                g.enableScissor(innerX, s.contentY, innerX + innerW, s.contentY + s.contentH);
                s.module.renderContent(this, g, font, innerX, s.contentY, innerW, mx, my);
                g.disableScissor();
            }
        }
        g.disableScissor();
        Ui.alpha = panelAlpha;

        // scrollbar
        if (maxMScroll > 0) {
            int thumbH = Math.max(18, (int) (viewH * (viewH / (float) (totalContent + 2))));
            int thumbY = viewTop + Math.round((mScroll / maxMScroll) * (viewH - thumbH));
            Ui.rrect(g, px + PANEL_W - 7, thumbY, 3, thumbH, 1, 0xFFC3C8CF);
        }

        Ui.textCenter(g, font, "LMB toggle  |  RMB settings  |  Scroll", px + PANEL_W / 2,
                py + panelH - FOOTER + 2, Ui.TEXT_DIM);
    }

    /** Module name on one line, or wrapped onto two lines at a space if it is too wide for the card. */
    private void drawCardName(GuiGraphics g, String name, int x, int y, int maxW, int color) {
        if (Ui.width(font, name) <= maxW) {
            Ui.text(g, font, name, x, y + 5, color);
            return;
        }
        int split = -1;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == ' ' && Ui.width(font, name.substring(0, i)) <= maxW) split = i;
        }
        if (split < 0) {
            Ui.text(g, font, Ui.fit(font, name, maxW), x, y + 5, color);
            return;
        }
        Ui.text(g, font, name.substring(0, split), x, y, color);
        Ui.text(g, font, Ui.fit(font, name.substring(split + 1), maxW), x, y + 11, color);
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
        for (Slot s : slots) {
            if (s.module.captureKey(event.key())) return true;   // e.g. Freecam keybind capture (ESC clears it)
        }
        boolean typing = this.getFocused() instanceof EditBox;
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
        for (Slot s : slots) {
            if (s.module.captureMouse(btn)) return true;   // middle / side mouse buttons can be bound as keybinds
        }
        for (Slot s : slots) s.module.cancelCapture();

        // sidebar categories
        {
            Category[] cats = Category.values();
            int top = py + 12;
            for (int i = 0; i < cats.length; i++) {
                if (Ui.in(mx, my, sx + 8, top + i * (CAT_H + CAT_GAP), SIDE_W - 16, CAT_H)) {
                    if (cats[i] != category) {
                        category = cats[i];
                        modFade = 0f;
                        Ui.click(1.05f);
                    }
                    return true;
                }
            }
        }

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

        int innerX = px + PAD;
        int innerW = PANEL_W - 2 * PAD;
        if (my >= viewTop && my < viewTop + viewH) {          // only things inside the scroll box can be clicked
            for (Slot s : vis) {
                if (Ui.in(mx, my, s.cardX, s.cardY, s.cardW, CARD_H)) {
                    if (btn == 0) {
                        s.module.setEnabled(!s.module.isEnabled());
                        Ui.click(s.module.isEnabled() ? 1.2f : 0.85f);
                    } else if (btn == 1 && s.module.contentHeight() > 0) {
                        s.expanded = !s.expanded;
                        Ui.click(s.expanded ? 1.05f : 0.9f);
                    }
                    return true;
                }
                if (s.anim > 0.9f && Ui.in(mx, my, innerX, s.contentY, innerW, s.contentH)) {
                    if (s.module.clickContent(this, mx, my, btn)) dragging = s;
                    return true;
                }
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
        if (Ui.in(mx, my, px, viewTop, PANEL_W, viewH)) {
            mScrollTarget -= (float) (scrollY * 28);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
