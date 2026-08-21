package com.steve.ai.entity;

import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkForceTrackerTest extends AbstractMinecraftTest {

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, new net.minecraft.resources.ResourceLocation("minecraft", "overworld"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, new net.minecraft.resources.ResourceLocation("minecraft", "the_nether"));

    private static final ChunkPos POS = new ChunkPos(3, -7);

    @Test
    void firstForceLoadsChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        assertTrue(tracker.force(OVERWORLD, POS, uuid));
        assertEquals(1, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void sameUuidDoesNotDoubleLoad() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        assertTrue(tracker.force(OVERWORLD, POS, uuid));
        assertFalse(tracker.force(OVERWORLD, POS, uuid)); // same holder - no second load
        assertEquals(1, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void secondHolderDoesNotDoubleLoadButCounts() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(tracker.force(OVERWORLD, POS, a));
        assertFalse(tracker.force(OVERWORLD, POS, b)); // already forced, but second holder
        assertEquals(2, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void lastUnforceReleasesChunk() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        tracker.force(OVERWORLD, POS, a);
        tracker.force(OVERWORLD, POS, b);
        assertFalse(tracker.unforce(OVERWORLD, POS, a)); // one holder remains
        assertTrue(tracker.unforce(OVERWORLD, POS, b));  // last holder - release
        assertEquals(0, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void unforceUnknownChunkIsNoop() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        assertFalse(tracker.unforce(OVERWORLD, POS, UUID.randomUUID()));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void unforceUnknownUuidIsNoop() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        tracker.force(OVERWORLD, POS, uuid);
        assertFalse(tracker.unforce(OVERWORLD, POS, UUID.randomUUID()));
        assertEquals(1, tracker.holders(OVERWORLD, POS));
    }

    @Test
    void chunksAreIndependent() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        ChunkPos other = new ChunkPos(10, 10);
        tracker.force(OVERWORLD, POS, uuid);
        tracker.force(OVERWORLD, other, uuid);
        assertTrue(tracker.unforce(OVERWORLD, POS, uuid));
        assertTrue(tracker.unforce(OVERWORLD, other, uuid));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
        assertEquals(0, tracker.holders(OVERWORLD, other));
    }

    @Test
    void hasHolderReflectsUuid() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        tracker.force(OVERWORLD, POS, uuid);
        assertTrue(tracker.hasHolder(OVERWORLD, POS, uuid));
        assertFalse(tracker.hasHolder(OVERWORLD, POS, UUID.randomUUID()));
        assertFalse(tracker.hasHolder(OVERWORLD, new ChunkPos(0, 0), uuid));
    }

    @Test
    void sameChunkInDifferentDimensionsIsIndependent() {
        ChunkForceTracker tracker = new ChunkForceTracker();
        UUID uuid = UUID.randomUUID();
        tracker.force(OVERWORLD, POS, uuid);
        tracker.force(NETHER, POS, uuid);
        assertEquals(1, tracker.holders(OVERWORLD, POS));
        assertEquals(1, tracker.holders(NETHER, POS));
        // Un-forcing the overworld chunk must NOT release the nether chunk
        assertTrue(tracker.unforce(OVERWORLD, POS, uuid));
        assertEquals(0, tracker.holders(OVERWORLD, POS));
        assertEquals(1, tracker.holders(NETHER, POS));
        assertTrue(tracker.unforce(NETHER, POS, uuid));
        assertEquals(0, tracker.holders(NETHER, POS));
    }
}
