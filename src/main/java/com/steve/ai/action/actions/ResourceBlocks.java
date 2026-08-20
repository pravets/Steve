package com.steve.ai.action.actions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared block resolution for mining/gathering tasks: maps LLM resource names
 * ("iron", "wood", "oak_log") to actual {@link Block} instances.
 */
public final class ResourceBlocks {

    /**
     * Describes what blocks can be mined to satisfy a resource request and what
     * item(s) count as the requested yield (e.g. iron ore → raw iron).
     */
    public record ResourceYield(
        Set<Block> miningBlocks,
        Predicate<Item> itemMatcher,
        Item representativeItem,
        String label
    ) {}

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

    private static final Map<String, ResourceYield> RESOURCE_TO_YIELD = Map.ofEntries(
        Map.entry("iron", new ResourceYield(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
            item -> item == Items.RAW_IRON, Items.RAW_IRON, "Raw Iron")),
        Map.entry("diamond", new ResourceYield(Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
            item -> item == Items.DIAMOND, Items.DIAMOND, "Diamond")),
        Map.entry("coal", new ResourceYield(Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            item -> item == Items.COAL, Items.COAL, "Coal")),
        Map.entry("gold", new ResourceYield(Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
            item -> item == Items.RAW_GOLD, Items.RAW_GOLD, "Raw Gold")),
        Map.entry("copper", new ResourceYield(Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
            item -> item == Items.RAW_COPPER, Items.RAW_COPPER, "Raw Copper")),
        Map.entry("redstone", new ResourceYield(Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            item -> item == Items.REDSTONE, Items.REDSTONE, "Redstone")),
        Map.entry("lapis", new ResourceYield(Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
            item -> item == Items.LAPIS_LAZULI, Items.LAPIS_LAZULI, "Lapis Lazuli")),
        Map.entry("emerald", new ResourceYield(Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
            item -> item == Items.EMERALD, Items.EMERALD, "Emerald")),
        Map.entry("stone", new ResourceYield(Set.of(Blocks.STONE, Blocks.COBBLESTONE),
            item -> item == Items.COBBLESTONE, Items.COBBLESTONE, "Cobblestone")),
        Map.entry("cobblestone", new ResourceYield(Set.of(Blocks.STONE, Blocks.COBBLESTONE),
            item -> item == Items.COBBLESTONE, Items.COBBLESTONE, "Cobblestone")),
        Map.entry("dirt", new ResourceYield(Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK),
            item -> item == Items.DIRT, Items.DIRT, "Dirt")),
        Map.entry("gravel", new ResourceYield(Set.of(Blocks.GRAVEL),
            item -> item == Items.GRAVEL, Items.GRAVEL, "Gravel")),
        Map.entry("sand", new ResourceYield(Set.of(Blocks.SAND),
            item -> item == Items.SAND, Items.SAND, "Sand"))
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
     * Returns the {@link ResourceYield} for a requested resource, mapping the
     * resource name to the blocks that can be mined and the items that count as
     * the yield. Unknown explicit block names fall back to the block's own item.
     */
    public static ResourceYield yieldFor(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        String normalized = resourceName.toLowerCase(Locale.ROOT).replace(" ", "_");
        ResourceYield yield = RESOURCE_TO_YIELD.get(normalized);
        if (yield != null) {
            return yield;
        }
        // Fallback for explicit block names not in the yield registry.
        Block block = parseBlock(resourceName);
        if (block == null) {
            return null;
        }
        Item item = block.asItem();
        if (item == Items.AIR) {
            item = Item.byBlock(block);
        }
        final Item fallbackItem = item;
        String label = block.getName().getString();
        return new ResourceYield(Set.of(block), i -> i == fallbackItem, fallbackItem, label);
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

    /** Whether the request means "any logs" (wood/tree), not one specific block. */
    public static boolean isWoodRequest(String resource) {
        if (resource == null) {
            return false;
        }
        String normalized = resource.toLowerCase(Locale.ROOT).replace("_", " ").trim();
        // Exact generic words are always wood requests, even though the
        // shorthand map aliases "wood" to oak_log.
        if (WOOD_EXACT.contains(normalized)) {
            return true;
        }
        // A concrete block name (oak_log, birch_wood) is NOT a wood request,
        // even though it contains the word "log".
        if (parseBlock(resource) != null) {
            return false;
        }
        for (String wood : WOOD_SYNONYMS) {
            if (normalized.contains(wood)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> WOOD_EXACT = java.util.Set.of(
        "wood", "tree", "trees", "log", "logs", "timber",
        "дерево", "дерева", "брёвна", "бревна", "брёвен", "бревен", "лес", "дрова", "древесина"
    );

    private static final List<String> WOOD_SYNONYMS = List.of(
        "wood", "tree", "log", "timber",
        "дерев", "брев", "брёв", "лес", "дров", "древесин"
    );
}
