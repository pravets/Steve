package ru.pravets.vasyan.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference-counted chunk force-loading (issue: Steves must keep working on a
 * server without players - entities only tick in loaded chunks).
 *
 * <p>Multiple Steves in one chunk must not fight over the force flag: the
 * first force() actually loads the chunk, the last unforce() releases it.
 * Holders are tracked by UUID so that a Steve reloaded from NBT after a chunk
 * unload does not double-count the force that was intentionally kept across
 * unload (issue #14).</p>
 *
 * <p>Counts are keyed by (dimension, chunk) - the same chunk coordinates in
 * different dimensions are different chunks (nether [18,0] is not overworld
 * [18,0]). Pure logic - unit-testable without a world.</p>
 */
public class ChunkForceTracker {

    /** (dimension, chunk position) composite key. */
    public record ChunkKey(ResourceKey<Level> dimension, ChunkPos pos) {
    }

    private final Map<ChunkKey, Set<UUID>> holders = new ConcurrentHashMap<>();

    /**
     * Records that the given Steve owns a force-load on this chunk.
     *
     * @return true when the chunk should actually be force-loaded
     *         (first holder), false when it was already forced
     */
    public boolean force(ResourceKey<Level> dimension, ChunkPos pos, UUID uuid) {
        Set<UUID> set = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<UUID> previous = holders.putIfAbsent(new ChunkKey(dimension, pos), set);
        if (previous != null) {
            set = previous;
        }
        return set.add(uuid) && set.size() == 1;
    }

    /**
     * Removes the given Steve from the force holders.
     *
     * @return true when the chunk should actually be un-forced
     *         (last holder left), false otherwise
     */
    public boolean unforce(ResourceKey<Level> dimension, ChunkPos pos, UUID uuid) {
        ChunkKey key = new ChunkKey(dimension, pos);
        Set<UUID> set = holders.get(key);
        if (set == null) {
            return false;
        }
        if (!set.remove(uuid)) {
            return false;
        }
        if (set.isEmpty()) {
            holders.remove(key);
            return true;
        }
        return false;
    }

    /**
     * @return true when the given UUID already holds this chunk
     */
    public boolean hasHolder(ResourceKey<Level> dimension, ChunkPos pos, UUID uuid) {
        Set<UUID> set = holders.get(new ChunkKey(dimension, pos));
        return set != null && set.contains(uuid);
    }

    public int holders(ResourceKey<Level> dimension, ChunkPos pos) {
        Set<UUID> set = holders.get(new ChunkKey(dimension, pos));
        return set == null ? 0 : set.size();
    }
}
