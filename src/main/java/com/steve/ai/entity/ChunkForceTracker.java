package com.steve.ai.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference-counted chunk force-loading (issue: Steves must keep working on a
 * server without players - entities only tick in loaded chunks).
 *
 * <p>Multiple Steves in one chunk must not fight over the force flag: the
 * first force() actually loads the chunk, the last unforce() releases it.
 * Counts are keyed by (dimension, chunk) - the same chunk coordinates in
 * different dimensions are different chunks (nether [18,0] is not overworld
 * [18,0]). Pure logic - unit-testable without a world.</p>
 */
public class ChunkForceTracker {

    /** (dimension, chunk position) composite key. */
    public record ChunkKey(ResourceKey<Level> dimension, ChunkPos pos) {
    }

    private final Map<ChunkKey, Integer> counts = new ConcurrentHashMap<>();

    /**
     * @return true when the chunk should actually be force-loaded
     *         (count went 0 -> 1), false when it was already forced
     */
    public boolean force(ResourceKey<Level> dimension, ChunkPos pos) {
        return counts.merge(new ChunkKey(dimension, pos), 1, Integer::sum) == 1;
    }

    /**
     * @return true when the chunk should actually be un-forced
     *         (count went 1 -> 0), false otherwise (other holders remain)
     */
    public boolean unforce(ResourceKey<Level> dimension, ChunkPos pos) {
        ChunkKey key = new ChunkKey(dimension, pos);
        Integer count = counts.get(key);
        if (count == null || count <= 0) {
            return false; // not forced by us - no-op
        }
        if (count == 1) {
            counts.remove(key);
            return true;
        }
        counts.put(key, count - 1);
        return false;
    }

    public int holders(ResourceKey<Level> dimension, ChunkPos pos) {
        return counts.getOrDefault(new ChunkKey(dimension, pos), 0);
    }
}
