package com.steve.ai.entity;

import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkForceTrackerTest extends AbstractMinecraftTest {

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, new net.minecraft.resources.ResourceLocation("minecraft", "overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, new net.minecraft.resources.ResourceLocation("minecraft", "the_nether"));

    private static final ChunkPos POS = new ChunkPos(3, -7);

    @Test
    void firstForceLoadsChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertTrue(tracker.force(OVERWORLD, POS));
        assertEquals(1, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void secondForceDoesNotDoubleLoad() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertTrue(tracker.force(OVERWORLD, POS));
        assertFalse(tracker.force(OVERWORLD, POS)); // already forced - no second load
        assertEquals(2, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void lastUnforceReleasesChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        tracker.force(OVERWORLD, POS);
        tracker.force(OVERWORLD, POS);
        assertFalse(tracker.unforce(OVERWORLD, POS)); // one holder remains
        assertTrue(tracker.unforce(OVERWORLD, POS));  // last holder - release
        assertEquals(0, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void unforceUnknownChunkIsNoop() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertFalse(tracker.unforce(OVERWORLD, POS));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void chunksAreIndependent() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        ChunkPos other = new ChunkPos(10, 10);
        tracker.force(OVERWORLD, POS);
        tracker.force(OVERWORLD, other);
        assertTrue(tracker.unforce(OVERWORLD, POS));
        assertTrue(tracker.unforce(OVERWORLD, other));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
        assertEquals(0, tracker.holders(OVERWORLD, other));
    }

    @Test
    void sameChunkInDifferentDimensionsIsIndependent() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        tracker.force(OVERWORLD, POS);
        tracker.force(NETHER, POS);
        assertEquals(1, tracker.holders(OVERWORLD, POS));
        assertEquals(1, tracker.holders(NETHER, POS));
        // Un-forcing the overworld chunk must NOT release the nether chunk
        assertTrue(tracker.unforce(OVERWORLD, POS));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
        assertEquals(1, tracker.holders(NETHER, POS));
        assertTrue(tracker.unforce(NETHER, POS));
        assertEquals(0, tracker.holders(NETHER, POS));
    }
}
