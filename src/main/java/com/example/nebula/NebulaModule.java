package com.example.nebula;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A feature that shows up as a row in the Nebula GUI. Add more by implementing this. */
public interface NebulaModule {
    String name();

    /** Which sidebar category this module lives in (never ALL). */
    Category category();

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

    /** Called when the GUI opens so a module can add text boxes etc. (see NebulaScreen#addModuleWidget). */
    default void initWidgets(NebulaScreen screen, Font font) {}

    /** Called when the GUI closes. */
    default void onScreenClosed() {}

    /** Called for every key press while the GUI is open. Return true if the module used it (e.g. keybind capture). */
    default boolean captureKey(int keyCode) { return false; }

    /** Called for every mouse click while the GUI is open (before anything else). Return true if used, e.g. binding a side button. */
    default boolean captureMouse(int button) { return false; }

    /** Called on every mouse click; modules waiting for a key should stop waiting. */
    default void cancelCapture() {}
}
