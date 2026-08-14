package com.steve.ai.memory;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for vision helpers (no Minecraft level needed).
 */
class VisionUtilsTest {

    @Test
    void directionIsEastWhenTargetIsEast() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(10, 64, 0);
        assertEquals("E", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsSouthWhenTargetIsSouthWestButSouthDominates() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(-5, 64, 8);
        assertEquals("S", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsUpWhenTargetIsAbove() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(0, 80, 0);
        assertEquals("up", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsDownNorthWhenTargetIsBelowAndNorth() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(0, 40, -3);
        assertEquals("down-N", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsHereWhenSamePosition() {
        BlockPos pos = new BlockPos(5, 64, 5);
        assertEquals("here", VisionUtils.directionTo(pos, pos));
    }

    @Test
    void nearestOfPicksClosestPosition() {
        BlockPos center = new BlockPos(0, 64, 0);
        List<BlockPos> positions = List.of(
            new BlockPos(20, 64, 0),
            new BlockPos(3, 64, 0),
            new BlockPos(15, 64, 0)
        );
        assertEquals(new BlockPos(3, 64, 0), VisionUtils.nearestOf(positions, center));
    }
}
