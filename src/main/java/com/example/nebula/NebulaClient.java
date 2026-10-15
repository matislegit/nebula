package com.example.nebula;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
    public static HudModule HUD;
    public static PlayerEspModule PLAYER_ESP;
    public static NametagsModule NAMETAGS;
    public static NotificationsModule NOTIFICATIONS;
    public static SusChunkFinderModule SUS_CHUNKS;
    public static LivePlayerDetectorModule LIVE_PLAYERS;
    public static SwingSpeedModule SWING_SPEED;
    public static SkinProtectModule SKIN_PROTECT;
    public static NameProtectModule NAME_PROTECT;
    public static List<NebulaModule> MODULES;
    public static KeyMapping OPEN_GUI;
    public static KeyMapping FREECAM_KEY;

    @Override
    public void onInitializeClient() {
        CONFIG = NebulaConfig.load();
        BLOCK_ESP = new BlockEspModule(CONFIG);
        FREECAM = new FreecamModule(CONFIG);
        HUD = new HudModule(CONFIG);
        PLAYER_ESP = new PlayerEspModule(CONFIG);
        NAMETAGS = new NametagsModule(CONFIG);
        NOTIFICATIONS = new NotificationsModule(CONFIG);
        SWING_SPEED = new SwingSpeedModule(CONFIG);
        SKIN_PROTECT = new SkinProtectModule(CONFIG);
        NAME_PROTECT = new NameProtectModule(CONFIG);
        SUS_CHUNKS = new SusChunkFinderModule(CONFIG);
        LIVE_PLAYERS = new LivePlayerDetectorModule(CONFIG);
        MODULES = List.of(HUD, NOTIFICATIONS, BLOCK_ESP, PLAYER_ESP, NAMETAGS, SWING_SPEED, SUS_CHUNKS, LIVE_PLAYERS, FREECAM, SKIN_PROTECT, NAME_PROTECT);   // add future modules here

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
            SKIN_PROTECT.tick(client);
            SUS_CHUNKS.tick(client);
            LIVE_PLAYERS.tick(client);
        });

        // the HUD module's on-screen list of enabled modules
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "module_list"), HudModule::render);
        // notification pop-ups (bottom corner)
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "notifications"), NotificationsModule::render);
        // player name tags (items, health, distance, totems)
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "nametags"), NametagsModule::render);

        EspRenderer.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            EspRenderer.close();
            CONFIG.save();
        });
    }
}
