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
