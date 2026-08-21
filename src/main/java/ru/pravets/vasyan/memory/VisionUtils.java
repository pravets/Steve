package ru.pravets.vasyan.memory;

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
     * Compass direction from one position to another: 8-directional
     * (N, NE, E, SE, S, SW, W, NW) optionally combined with "up"/"down",
     * e.g. "E", "SW", "up-N". Returns "here" only when positions are identical.
     */
    public static String directionTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();

        StringBuilder dir = new StringBuilder();
        if (dy > 0) dir.append("up");
        else if (dy < 0) dir.append("down");

        // 8-directional horizontal: diagonal when both axes are comparable
        String horizontal = "";
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        if (ax > 0 || az > 0) {
            boolean diagonal = ax > 0 && az > 0
                && ax >= az / 2 && az >= ax / 2;
            if (diagonal) {
                horizontal = (dz > 0 ? "S" : "N") + (dx > 0 ? "E" : "W");
            } else if (ax > az) {
                horizontal = dx > 0 ? "E" : "W";
            } else {
                horizontal = dz > 0 ? "S" : "N";
            }
        }
        if (!horizontal.isEmpty()) {
            if (dir.length() > 0) dir.append("-");
            dir.append(horizontal);
        }
        return dir.length() > 0 ? dir.toString() : "here";
    }
}
