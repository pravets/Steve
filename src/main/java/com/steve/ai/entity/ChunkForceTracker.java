package com.steve.ai.entity;

import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference-counted chunk force-loading (issue: Steves must keep working on a
 * server without players - entities only tick in loaded chunks).
 *
 * <p>Multiple Steves in one chunk must not fight over the force flag: the
 * first force() actually loads the chunk, the last unforce() releases it.
 * Pure logic - unit-testable without a world.</p>
 */
public class ChunkForceTracker {

    private final Map<ChunkPos, Integer> counts = new ConcurrentHashMap<>();

    /**
     * @return true when the chunk should actually be force-loaded
     *         (count went 0 -> 1), false when it was already forced
     */
    public boolean force(ChunkPos pos) {
        return counts.merge(pos, 1, Integer::sum) == 1;
    }

    /**
     * @return true when the chunk should actually be un-forced
     *         (count went 1 -> 0), false otherwise (other holders remain)
     */
    public boolean unforce(ChunkPos pos) {
        Integer count = counts.get(pos);
        if (count == null || count <= 0) {
            return false; // not forced by us - no-op
        }
        if (count == 1) {
            counts.remove(pos);
            return true;
        }
        counts.put(pos, count - 1);
        return false;
    }

    public int holders(ChunkPos pos) {
        return counts.getOrDefault(pos, 0);
    }
}
