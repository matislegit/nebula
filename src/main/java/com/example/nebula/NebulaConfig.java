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
    public int blockEspRadiusChunks = 6;      // scan radius in chunks (edit in the json if you want more)
    public List<String> blockEspBlocks = new ArrayList<>();
    public float freecamSpeed = 1.0f;         // 1 .. 10 (1 = normal walking speed)

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
                    c.blockEspRadiusChunks = Math.max(1, Math.min(16, c.blockEspRadiusChunks));
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
