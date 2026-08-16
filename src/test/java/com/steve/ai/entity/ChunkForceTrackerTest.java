package com.steve.ai.entity;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkForceTrackerTest {

    private static final ChunkPos POS = new ChunkPos(3, -7);

    @Test
    void firstForceLoadsChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertTrue(tracker.force(POS));
        assertEquals(1, tracker.holders(POS));
    }

    @Test
    void secondForceDoesNotDoubleLoad() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertTrue(tracker.force(POS));
        assertFalse(tracker.force(POS)); // already forced - no second load
        assertEquals(2, tracker.holders(POS));
    }

    @Test
    void lastUnforceReleasesChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        tracker.force(POS);
        tracker.force(POS);
        assertFalse(tracker.unforce(POS)); // one holder remains
        assertTrue(tracker.unforce(POS));  // last holder - release
        assertEquals(0, tracker.holders(POS));
    }

    @Test
    void unforceUnknownChunkIsNoop() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertFalse(tracker.unforce(POS));
        assertEquals(0, tracker.holders(POS));
    }

    @Test
    void chunksAreIndependent() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        ChunkPos other = new ChunkPos(10, 10);
        tracker.force(POS);
        tracker.force(other);
        assertTrue(tracker.unforce(POS));
        assertTrue(tracker.unforce(other));
        assertEquals(0, tracker.holders(POS));
        assertEquals(0, tracker.holders(other));
    }
}
