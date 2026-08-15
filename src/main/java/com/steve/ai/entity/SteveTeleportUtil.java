package com.steve.ai.entity;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Pure geometry helper for finding a safe spot to teleport a Steve to.
 * No world access - the caller supplies a predicate that checks whether
 * a given block position is acceptable (solid ground, air, not liquid).
 */
public final class SteveTeleportUtil {

    /** Maximum distance (in blocks) searched around the target. */
    private static final int SEARCH_RADIUS = 3;

    @FunctionalInterface
    public interface SafePosPredicate {
        boolean test(int x, int y, int z);
    }

    private SteveTeleportUtil() {}

    /**
     * Finds the nearest acceptable position around {@code center}, scanning
     * rings of increasing radius (1..{@value #SEARCH_RADIUS}). Within a ring,
     * positions at the same height are preferred, then one block above,
     * then one below. The center itself is never returned.
     *
     * @return first acceptable position, or {@code null} if none found
     */
    @Nullable
    public static BlockPos findSafePos(BlockPos center, SafePosPredicate ok) {
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int radius = 1; radius <= SEARCH_RADIUS; radius++) {
            // Same height first, then above, then below
            for (int dy : new int[] {0, 1, -1}) {
                int y = cy + dy;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                            continue; // only the ring, not the interior
                        }
                        if (ok.test(cx + dx, y, cz + dz)) {
                            return new BlockPos(cx + dx, y, cz + dz);
                        }
                    }
                }
            }
        }
        return null;
    }
}
