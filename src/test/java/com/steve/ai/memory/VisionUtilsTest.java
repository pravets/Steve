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
    void directionIsSouthWestWhenBothAxesComparable() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(-5, 64, 8);
        assertEquals("SW", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsNorthEastWhenBothAxesComparable() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(6, 64, -4);
        assertEquals("NE", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsUpWhenTargetIsOneBlockAbove() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(0, 65, 0);
        assertEquals("up", VisionUtils.directionTo(from, to));
    }

    @Test
    void directionIsDownWhenTargetIsTwoBlocksBelow() {
        BlockPos from = new BlockPos(0, 64, 0);
        BlockPos to = new BlockPos(0, 62, 0);
        assertEquals("down", VisionUtils.directionTo(from, to));
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
