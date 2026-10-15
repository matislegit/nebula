package com.example.nebula;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

/** Saved to .minecraft/config/nebula.json */
public final class NebulaConfig {
    public boolean blockEspEnabled = false;
    public float blockEspOpacity = 0.5f;      // 0.05 .. 1.0
    public boolean blockEspTracers = false;
    public int blockEspRadiusOverride = 0;    // 0 = automatic (your render distance); or set a chunk radius here
    public List<String> blockEspBlocks = new ArrayList<>();
    public boolean playerEspEnabled = false;
    public int playerEspMode = 0;             // 0 = Filled Box, 1 = Box, 2 = Skeleton
    public int playerEspColor = 0xA03CF0;     // RGB, default purple
    public float playerEspOpacity = 0.6f;     // 0.05 .. 1.0
    public boolean nametagsEnabled = false;
    public boolean ntArmor = true;
    public boolean ntOffhand = true;
    public boolean ntMainhand = true;
    public boolean ntHealth = true;
    public boolean ntDistance = true;
    public boolean ntTotems = true;
    public boolean nameProtectEnabled = false;
    public String nameProtectName = "Nebula";
    public boolean notificationsEnabled = true;
    public boolean notificationsRight = true;   // false = bottom-left corner, true = bottom-right corner
    public boolean susTracers = false;
    public boolean liveTracers = false;
    public boolean susEnabled = false;
    public int susSensitivity = 5;            // 1 .. 10
    public boolean susAmethyst = true;
    public boolean susKelp = true;
    public boolean susCaveVines = true;
    public boolean susVines = true;
    public boolean susBamboo = true;
    public boolean susDeepslate = true;
    public boolean liveEnabled = false;
    public boolean hudEnabled = false;
    public boolean hudRight = false;          // false = list on the left, true = right
    public boolean swingSpeedEnabled = false;
    public int swingSpeed = 15;               // 1 .. 30 (15 = normal vanilla swing speed, lower = slower)
    public boolean skinProtectEnabled = false;
    public String skinProtectName = "Nebula";
    public float freecamSpeed = 1.0f;         // 1 .. 10 (the slider)
    public float freecamBlocksPerSecond = 15f; // how fast 1.0x is (edit in the json to taste)

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("nebula.json");
    }

    public static NebulaConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                NebulaConfig c = GSON.fromJson(r, NebulaConfig.class);
                if (c != null) {
                    if (c.blockEspBlocks == null) c.blockEspBlocks = new ArrayList<>();
                    c.blockEspOpacity = Math.max(0.05f, Math.min(1f, c.blockEspOpacity));
                    c.blockEspRadiusOverride = Math.max(0, Math.min(34, c.blockEspRadiusOverride));
                    c.freecamBlocksPerSecond = Math.max(1f, Math.min(200f, c.freecamBlocksPerSecond));
                    c.freecamSpeed = Math.max(1f, Math.min(10f, c.freecamSpeed));
                    c.swingSpeed = Math.max(1, Math.min(30, c.swingSpeed));
                    c.playerEspMode = Math.max(0, Math.min(2, c.playerEspMode));
                    c.playerEspOpacity = Math.max(0.05f, Math.min(1f, c.playerEspOpacity));
                    c.playerEspColor &= 0xFFFFFF;
                    if (c.nameProtectName == null) c.nameProtectName = "Nebula";
                    c.susSensitivity = Math.max(1, Math.min(10, c.susSensitivity));
                    if (c.skinProtectName == null || !c.skinProtectName.matches("[A-Za-z0-9_]{3,16}")) c.skinProtectName = "Nebula";
                    return c;
                }
            } catch (Exception e) {
                NebulaClient.LOGGER.warn("Could not read nebula.json, using defaults", e);
            }
        }
        return new NebulaConfig();
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(this, w);
        } catch (IOException e) {
            NebulaClient.LOGGER.warn("Could not save nebula.json", e);
        }
    }
}
