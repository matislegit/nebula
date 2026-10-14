package com.example.nebula;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NebulaClient implements ClientModInitializer {
    public static final String MOD_ID = "nebula";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static NebulaConfig CONFIG;
    public static BlockEspModule BLOCK_ESP;
    public static FreecamModule FREECAM;
    public static List<NebulaModule> MODULES;
    public static KeyMapping OPEN_GUI;
    public static KeyMapping FREECAM_KEY;

    @Override
    public void onInitializeClient() {
        CONFIG = NebulaConfig.load();
        BLOCK_ESP = new BlockEspModule(CONFIG);
        FREECAM = new FreecamModule(CONFIG);
        MODULES = List.of(BLOCK_ESP, FREECAM);          // add future modules here

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        OPEN_GUI = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nebula.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,        // default: \  (change it in Options > Controls > Key Binds)
                category));
        // Unbound by default. Set it from the Freecam dropdown in the GUI or in Options > Controls.
        FREECAM_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nebula.freecam",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_GUI.consumeClick()) {
                if (client.screen == null && client.level != null) {
                    client.setScreen(new NebulaScreen());
                }
            }
            while (FREECAM_KEY.consumeClick()) {
                if (client.screen == null && client.level != null && client.player != null) {
                    FREECAM.toggle(client);
                }
            }
            FREECAM.tick(client);
            BLOCK_ESP.tick(client);
        });

        EspRenderer.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            EspRenderer.close();
            CONFIG.save();
        });
    }
}
