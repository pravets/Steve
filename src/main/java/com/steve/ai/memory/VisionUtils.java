package com.steve.ai.memory;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Pure geometry helpers for vision logic. No Minecraft registry dependencies,
 * safe to unit-test in plain JUnit.
 */
public final class VisionUtils {

    private VisionUtils() {}

    /**
     * Returns the closest position to the center.
     */
    public static BlockPos nearestOf(List<BlockPos> positions, BlockPos center) {
        BlockPos nearest = positions.get(0);
        double best = center.distSqr(nearest);
        for (BlockPos p : positions) {
            double d = center.distSqr(p);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    /**
     * Compass direction from one position to another, e.g. "E", "SW", "up-N", "here".
     */
    public static String directionTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();

        StringBuilder dir = new StringBuilder();
        if (dy > 3) dir.append("up");
        else if (dy < -3) dir.append("down");

        String horizontal = "";
        if (Math.abs(dx) > Math.abs(dz)) {
            horizontal = dx > 0 ? "E" : "W";
        } else if (Math.abs(dz) > 0) {
            horizontal = dz > 0 ? "S" : "N";
        }
        if (!horizontal.isEmpty()) {
            if (dir.length() > 0) dir.append("-");
            dir.append(horizontal);
        }
        return dir.length() > 0 ? dir.toString() : "here";
    }
}
