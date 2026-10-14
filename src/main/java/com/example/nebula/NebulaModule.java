package com.example.nebula;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A feature that shows up as a row in the Nebula GUI. Add more by implementing this. */
public interface NebulaModule {
    String name();
    boolean isEnabled();
    void setEnabled(boolean enabled);

    /** Height of the dropdown (right-click) area. */
    int contentHeight();

    /** Draw the dropdown at (x, y) with width w. It is clipped while animating. */
    void renderContent(NebulaScreen screen, GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY);

    /** @return true if the click was consumed (and a drag may follow). */
    boolean clickContent(NebulaScreen screen, double mx, double my, int button);

    void dragContent(double mx, double my);

    void releaseContent();
}
