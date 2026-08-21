package com.steve.ai.action.actions;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourceSearchPlannerTest {

    private static final int RING_SPACING = 8;
    private static final int STATIONS_PER_RING = 8;

    @Test
    void firstStationIsFirstRingAtGroundLevel() {
        BlockPos origin = new BlockPos(0, 64, 0);
        var state = new ResourceSearchPlanner.SearchState(origin, 0, 0, 1000);

        BlockPos station = ResourceSearchPlanner.stationFor(state, RING_SPACING, STATIONS_PER_RING);

        // radius 8, angle 0 -> (8, 0), y = origin.y (ground level)
        assertEquals(new BlockPos(8, 64, 0), station);
    }

    @Test
    void nextAdvancesStationThenRing() {
        BlockPos origin = new BlockPos(0, 64, 0);

        var s0 = new ResourceSearchPlanner.SearchState(origin, 0, 0, 1000);
        var s1 = ResourceSearchPlanner.next(s0, STATIONS_PER_RING);
        assertEquals(0, s1.ringIndex());
        assertEquals(1, s1.stationIndex());

        var last = new ResourceSearchPlanner.SearchState(origin, 0, STATIONS_PER_RING - 1, 1000);
        var nextRing = ResourceSearchPlanner.next(last, STATIONS_PER_RING);
        assertEquals(1, nextRing.ringIndex());
        assertEquals(0, nextRing.stationIndex());
    }

    @Test
    void keepsOriginAndStartTickAcrossAdvance() {
        BlockPos origin = new BlockPos(10, 64, 10);
        var state = new ResourceSearchPlanner.SearchState(origin, 2, 3, 5000);
        var next = ResourceSearchPlanner.next(state, STATIONS_PER_RING);

        assertEquals(origin, next.origin());
        assertEquals(5000, next.startedAtTick());
    }

    @Test
    void timeoutBoundary() {
        var state = new ResourceSearchPlanner.SearchState(new BlockPos(0, 64, 0), 0, 0, 1000);

        assertFalse(ResourceSearchPlanner.isTimedOut(state, 1059, 60));
        assertTrue(ResourceSearchPlanner.isTimedOut(state, 1060, 60));
    }

    @Test
    void hasNextStopsAfterMaxRadius() {
        BlockPos origin = new BlockPos(0, 64, 0);
        int maxRadius = 32;

        // ring 3 -> radius 32 == maxRadius: still has next
        var ring3 = new ResourceSearchPlanner.SearchState(origin, 3, 0, 1000);
        assertTrue(ResourceSearchPlanner.hasNext(ring3, maxRadius, RING_SPACING));

        // ring 4 -> radius 40 > maxRadius: done
        var ring4 = new ResourceSearchPlanner.SearchState(origin, 4, 0, 1000);
        assertFalse(ResourceSearchPlanner.hasNext(ring4, maxRadius, RING_SPACING));
    }

    @Test
    void stationsOnSameRingAreDistinct() {
        BlockPos origin = new BlockPos(0, 64, 0);
        Set<BlockPos> stations = new HashSet<>();
        for (int i = 0; i < STATIONS_PER_RING; i++) {
            var state = new ResourceSearchPlanner.SearchState(origin, 0, i, 1000);
            stations.add(ResourceSearchPlanner.stationFor(state, RING_SPACING, STATIONS_PER_RING));
        }
        assertEquals(STATIONS_PER_RING, stations.size());
    }
}
