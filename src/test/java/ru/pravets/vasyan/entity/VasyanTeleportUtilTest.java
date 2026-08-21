package ru.pravets.vasyan.entity;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VasyanTeleportUtilTest {

    @Test
    void findsNearestFreeSpotInFirstRing() {
        BlockPos center = new BlockPos(10, 64, 10);
        // Only (0,0,1) is free: must be found on the first ring, same height
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) ->
            x == 10 && y == 64 && z == 11);
        assertEquals(center.offset(0, 0, 1), found);
    }

    @Test
    void returnsNullWhenEverythingBlocked() {
        BlockPos center = new BlockPos(10, 64, 10);
        assertNull(VasyanTeleportUtil.findSafePos(center, (x, y, z) -> false));
    }

    @Test
    void searchesOuterRingWhenInnerOccupied() {
        BlockPos center = new BlockPos(10, 64, 10);
        // Only ring-2 spot free
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) ->
            x == 12 && y == 64 && z == 10);
        assertEquals(center.offset(2, 0, 0), found);
    }

    @Test
    void prefersSameHeightOverAbove() {
        BlockPos center = new BlockPos(10, 64, 10);
        // Both (0,0,1) and (0,1,1) free: same-height wins
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) ->
            (x == 10 && z == 11) && (y == 64 || y == 65));
        assertEquals(center.offset(0, 0, 1), found);
    }

    @Test
    void centerItselfIsNotConsidered() {
        BlockPos center = new BlockPos(10, 64, 10);
        // Everything free including the center: must still return a ring-1 spot.
        // Iteration order: dy=0 first, then dx from -1, dz from -1 -> (-1,0,-1).
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) -> true);
        assertEquals(center.offset(-1, 0, -1), found);
    }

    @Test
    void findsSpotBelowWhenSameHeightBlocked() {
        // Regression: flying player / pillar - spot 4 blocks below must be found,
        // previously the vertical search was limited to +-1 and returned null.
        BlockPos center = new BlockPos(10, 64, 10);
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) ->
            x == 10 && y == 60 && z == 11);
        assertEquals(center.offset(0, -4, 1), found);
    }

    @Test
    void prefersDownOverUp() {
        BlockPos center = new BlockPos(10, 64, 10);
        // Both one block below and one above are free: below wins
        BlockPos found = VasyanTeleportUtil.findSafePos(center, (x, y, z) ->
            (x == 10 && z == 11) && (y == 63 || y == 65));
        assertEquals(center.offset(0, -1, 1), found);
    }
}
