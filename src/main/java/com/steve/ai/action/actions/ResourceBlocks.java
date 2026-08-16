package com.steve.ai.action.actions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Locale;
import java.util.Map;

/**
 * Shared block resolution for mining/gathering tasks: maps LLM resource names
 * ("iron", "wood", "oak_log") to actual {@link Block} instances.
 */
public final class ResourceBlocks {

    private static final Map<String, String> RESOURCE_TO_BLOCK = Map.ofEntries(
        Map.entry("iron", "iron_ore"),
        Map.entry("diamond", "diamond_ore"),
        Map.entry("coal", "coal_ore"),
        Map.entry("gold", "gold_ore"),
        Map.entry("copper", "copper_ore"),
        Map.entry("redstone", "redstone_ore"),
        Map.entry("lapis", "lapis_ore"),
        Map.entry("emerald", "emerald_ore"),
        Map.entry("wood", "oak_log"),
        Map.entry("log", "oak_log"),
        Map.entry("tree", "oak_log"),
        Map.entry("stone", "stone"),
        Map.entry("cobblestone", "cobblestone"),
        Map.entry("dirt", "dirt"),
        Map.entry("gravel", "gravel"),
        Map.entry("sand", "sand")
    );

    private ResourceBlocks() {}

    /**
     * Resolves a resource/block name to a Block, or null if unknown.
     * Accepts: "iron", "wood", "oak_log", "minecraft:oak_log", ...
     */
    public static Block parseBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return null;
        }
        String normalized = blockName.toLowerCase(Locale.ROOT).replace(" ", "_");
        if (RESOURCE_TO_BLOCK.containsKey(normalized)) {
            normalized = RESOURCE_TO_BLOCK.get(normalized);
        }
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        ResourceLocation location = ResourceLocation.tryParse(normalized);
        if (location == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(location);
        return block == Blocks.AIR ? null : block;
    }

    /**
     * Max stack size of the item this block drops as (oak_log=64,
     * ender_pearl=16, ...). Used to resolve "добудь стак" deterministically.
     */
    public static int stackSizeFor(Block block) {
        if (block == null) {
            return 64;
        }
        net.minecraft.world.item.Item item = block.asItem();
        return item == net.minecraft.world.item.Items.AIR ? 64 : new net.minecraft.world.item.ItemStack(item).getMaxStackSize();
    }
}
