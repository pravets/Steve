package ru.pravets.vasyan.entity;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Pure geometry helper for finding a safe spot to teleport a Vasyan to.
 * No world access - the caller supplies a predicate that checks whether
 * a given block position is acceptable (solid ground, air, not liquid).
 */
public final class VasyanTeleportUtil {

    /** Maximum horizontal distance (in blocks) searched around the target. */
    private static final int SEARCH_RADIUS = 3;

    /** How far below the target we keep looking for solid ground (flying player). */
    private static final int SEARCH_DOWN = 16;

    /** How far above the target we look (player standing on a pillar). */
    private static final int SEARCH_UP = 3;

    @FunctionalInterface
    public interface SafePosPredicate {
        boolean test(int x, int y, int z);
    }

    private VasyanTeleportUtil() {}

    /**
     * Finds the nearest acceptable position around {@code center}, scanning
     * rings of increasing horizontal radius (1..{@value #SEARCH_RADIUS}).
     * For each horizontal position the vertical candidates are tried in
     * order: same height, then down (to {@value #SEARCH_DOWN} blocks),
     * then up (to {@value #SEARCH_UP} blocks). This way a flying player or
     * someone standing on a pillar still gets a safe spot on the ground.
     * The center itself is never returned.
     *
     * @return first acceptable position, or {@code null} if none found
     */
    @Nullable
    public static BlockPos findSafePos(BlockPos center, SafePosPredicate ok) {
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int radius = 1; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue; // only the ring, not the interior
                    }
                    // Same height first, then down, then up
                    if (ok.test(cx + dx, cy, cz + dz)) {
                        return new BlockPos(cx + dx, cy, cz + dz);
                    }
                    for (int dy = 1; dy <= SEARCH_DOWN; dy++) {
                        if (ok.test(cx + dx, cy - dy, cz + dz)) {
                            return new BlockPos(cx + dx, cy - dy, cz + dz);
                        }
                    }
                    for (int dy = 1; dy <= SEARCH_UP; dy++) {
                        if (ok.test(cx + dx, cy + dy, cz + dz)) {
                            return new BlockPos(cx + dx, cy + dy, cz + dz);
                        }
                    }
                }
            }
        }
        return null;
    }
}
