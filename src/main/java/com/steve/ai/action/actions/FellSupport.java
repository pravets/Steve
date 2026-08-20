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
     * (collision shape) block item. Prefers anything EXCEPT the target block
     * (dirt, cobble, ...), but falls back to the harvested logs themselves -
     * a log pillar is NOT a loss: it is dismantled and re-collected on the
     * way down. Without this fallback the bot stalls under the tree when it
     * has felled 3-4 logs but no dirt to climb with.
     * Returns EMPTY if there is nothing usable.
     */
    public static ItemStack findSolidPillarBlock(Level level, BlockPos standPos,
                                                 SteveInventory inventory, Block targetBlock) {
        ItemStack logFallback = ItemStack.EMPTY;
        for (ItemStack stack : inventory.getStacks()) {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            Block block = blockItem.getBlock();
            BlockState state = block.defaultBlockState();
            if (state.isAir() || state.liquid() || !state.isCollisionShapeFullBlock(level, standPos)) {
                continue;
            }
            if (block == targetBlock) {
                // remember as fallback, but prefer building blocks first
                if (logFallback.isEmpty()) {
                    logFallback = stack;
                }
                continue;
            }
            return stack;
        }
        return logFallback;
    }

    /** Squared horizontal distance from one block position to another. */
    public static double horizontalDistanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * True when any block in the given 3D radius (inclusive) matches the
     * predicate. Used to tell real trees (logs with leaves nearby) apart from
     * logs inside player structures - structures must never be felled.
     */
    public static boolean hasNearbyBlock(BlockPos center, Predicate<BlockPos> isBlock, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isBlock.test(pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
