package com.steve.ai.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Represents a block to be placed at a specific position.
 * Shared class used across structure generation, building actions, and collaborative builds.
 */
public class BlockPlacement {
    public final BlockPos pos;
    public final Block block;

    public BlockPlacement(BlockPos pos, Block block) {
        this.pos = pos;
        this.block = block;
    }
}
