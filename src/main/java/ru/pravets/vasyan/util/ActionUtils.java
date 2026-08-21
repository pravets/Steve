package com.steve.ai.util;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Common utility methods used across multiple action classes
 */
public class ActionUtils {

    /**
     * Find the nearest player to a Steve entity
     *
     * @param steve The Steve entity
     * @return The nearest player, or null if no players found
     */
    public static Player findNearestPlayer(SteveEntity steve) {
        List<? extends Player> players = steve.level().players();

        if (players.isEmpty()) {
            return null;
        }

        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }

            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Parse a block name string into a Block instance
     * Handles common resource names and aliases
     *
     * @param blockName The block name (e.g., "iron_ore", "diamond", "minecraft:stone")
     * @return The Block instance, or Blocks.AIR if not found
     */
    public static Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");

        // Add minecraft namespace if not present
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }

        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
        return block != null ? block : Blocks.AIR;
    }
}
