package com.steve.ai.action.actions;

import com.steve.ai.entity.SteveInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Support logic for whole-tree felling (nerd-pole climbing on real blocks).
 * Pure helpers - unit-testable without a world.
 */
public final class FellSupport {

    private static final BlockPos[] NEIGHBORS = {
        new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
        new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
        new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    private FellSupport() {}

    /**
     * Finds the first usable block stack for the pillar: a solid full-block
     * (collision shape) block item that is NOT the target block (never burn
     * the logs we are harvesting). Returns EMPTY if there is nothing usable.
     */
    public static ItemStack findSolidPillarBlock(Level level, BlockPos standPos,
                                                 SteveInventory inventory, Block targetBlock) {
        for (ItemStack stack : inventory.getStacks()) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            Block block = blockItem.getBlock();
            if (block == targetBlock) {
                continue; // don't burn logs
            }
            BlockState state = block.defaultBlockState();
            if (!state.isAir() && !state.liquid() && state.isCollisionShapeFullBlock(level, standPos)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Collects the connected component of log blocks reachable from
     * {@code start} (6-neighborhood BFS), capped at {@code maxLogs} - guards
     * against treating a whole forest as one tree. {@code start} itself must
     * be a log (it is included only if the predicate matches).
     */
    public static List<BlockPos> collectConnectedLogs(BlockPos start, Predicate<BlockPos> isLog, int maxLogs) {
        List<BlockPos> result = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty() && result.size() < maxLogs) {
            BlockPos pos = queue.poll();
            if (!isLog.test(pos)) {
                continue;
            }
            result.add(pos);
            for (BlockPos offset : NEIGHBORS) {
                BlockPos next = pos.offset(offset);
                if (visited.add(next) && isLog.test(next)) {
                    queue.add(next);
                }
            }
        }
        return result;
    }
}
