package com.example.nebula;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Every block in the game (including modded ones), built once on first use. */
public final class BlockCatalog {
    private BlockCatalog() {}

    public record Entry(Block block, String id, String name, String lowerName, String lowerId,
                        ItemStack stack, int color) {}

    private static List<Entry> ALL;
    private static final Map<Block, Integer> COLORS = new HashMap<>();

    public static List<Entry> all() {
        if (ALL == null) {
            List<Entry> list = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                // Air variants are skipped: they are "everywhere", so an ESP on them is meaningless.
                if (b.defaultBlockState().isAir()) continue;
                Identifier id = BuiltInRegistries.BLOCK.getKey(b);
                String name = b.getName().getString();
                list.add(new Entry(b, id.toString(), name, name.toLowerCase(Locale.ROOT),
                        id.toString().toLowerCase(Locale.ROOT), new ItemStack(b.asItem()), color(b)));
            }
            list.sort(Comparator.comparing(Entry::lowerName));
            ALL = list;
        }
        return ALL;
    }

    /** A stable, distinct colour per block type (ARGB, fully opaque). */
    public static int color(Block b) {
        return COLORS.computeIfAbsent(b, k -> {
            int h = BuiltInRegistries.BLOCK.getKey(k).toString().hashCode() * 0x9E3779B1;
            float hue = ((h >>> 8) & 0xFFFF) / 65535f;
            return 0xFF000000 | Mth.hsvToRgb(hue, 0.65f, 1.0f);
        });
    }
}
