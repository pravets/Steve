package ru.pravets.vasyan.entity;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.debug.AgentDebugBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

public class VasyanManager {
    private final Map<String, VasyanEntity> activeVasyans;
    private final Map<UUID, VasyanEntity> vasyansByUUID;

    public VasyanManager() {
        this.activeVasyans = new ConcurrentHashMap<>();
        this.vasyansByUUID = new ConcurrentHashMap<>();
    }

    /**
     * Finds the registry entry whose canonical name matches the given name
     * ignoring case.
     *
     * @param name the name to look up, may be null
     * @return the matching {@code Map.Entry} (canonical name + entity), or null
     */
    private Map.Entry<String, VasyanEntity> findEntryByNameIgnoreCase(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, VasyanEntity> entry : activeVasyans.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns the tracked Vasyan whose canonical name matches the given name
     * ignoring case.
     *
     * @param name the name to look up, may be null
     * @return the matching entity, or null if no Vasyan is tracked under a
     *         case-insensitive match
     */
    private VasyanEntity findByNameIgnoreCase(String name) {
        Map.Entry<String, VasyanEntity> entry = findEntryByNameIgnoreCase(name);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * Registers a VasyanEntity that entered the world (fresh spawn or loaded
     * from a chunk / NBT). If the name is already taken by another live
     * instance, the newcomer is a duplicate and gets discarded (dedup).
     *
     * <p>When the survivor and the newcomer disagree on origin, the instance
     * loaded from NBT wins: it carries the real persisted state (inventory,
     * memory), while a freshly spawned one is an empty replacement. This
     * protects the original bot from being destroyed in favour of an empty
     * copy when its chunk loads after a fresh spawn with the same name. When
     * both come from the same origin the first registered instance wins.
     *
     * <p>Re-adopting the same instance is a no-op (idempotent).
     *
     * <p>A duplicate newcomer that is rejected is returned as null and is
     * NOT discarded here: during an {@code EntityJoinLevelEvent} the entity
     * has not entered the world yet, so {@code ServerEventHandler} cancels
     * the join instead. {@code discard()} stays for instances that are
     * already in the world (the NBT-vs-fresh replacement below) and for
     * {@link #removeVasyan(String, MinecraftServer)}.
     *
     * @return the adopted instance, or null if it was rejected as a duplicate
     */
    public VasyanEntity adopt(VasyanEntity vasyan) {
        if (vasyan == null) {
            return null;
        }
        String name = requireNonNull(vasyan.getVasyanName(), "Vasyan name must not be null");
        Map.Entry<String, VasyanEntity> existingEntry = findEntryByNameIgnoreCase(name);
        VasyanEntity existing = existingEntry != null ? existingEntry.getValue() : null;
        if (existing != null) {
            if (existing == vasyan) {
                return vasyan;
            }
            String existingKey = existingEntry.getKey();
            if (!existing.isAlive() || existing.isRemoved()) {
                // Stale registry entry (e.g. survivor of a crash) - replace it
                activeVasyans.remove(existingKey);
                vasyansByUUID.remove(existing.getUUID());
            } else if (vasyan.isLoadedFromNbt() && !existing.isLoadedFromNbt()) {
                // The newcomer is the real bot loaded from NBT; the survivor is
                // a freshly spawned empty copy. Keep the NBT state, discard the
                // empty copy without dropping its (empty) inventory.
                VasyanMod.LOGGER.warn("Dedup discard: replacing fresh '{}' ({} alive={}, removed={}) with NBT-loaded original ({})",
                        name, existing.getUUID(), existing.isAlive(), existing.isRemoved(), vasyan.getUUID());
                existing.setSuppressInventoryDrop(true);
                existing.discard();
                VasyanMod.LOGGER.info("Dedup: replaced fresh duplicate '{}' ({}) with NBT-loaded original ({})",
                        name, existing.getUUID(), vasyan.getUUID());
                activeVasyans.remove(existingKey);
                vasyansByUUID.remove(existing.getUUID());
            } else {
                // Duplicate bot with the same name: reject the newcomer. It has
                // not entered the world yet during an EntityJoinLevelEvent, so
                // the join is canceled (see ServerEventHandler) instead of a
                // discard that would drop the (identical, duplicated) contents.
                VasyanMod.LOGGER.info("Dedup: rejected duplicate Vasyan '{}' ({}) - another live instance exists",
                        name, vasyan.getUUID());
                return null;
            }
        }
        activeVasyans.put(name, vasyan);
        vasyansByUUID.put(vasyan.getUUID(), vasyan);
        if (VasyanConfig.FORCE_LOAD_CHUNKS.get()
                && vasyan.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.ChunkPos current = new net.minecraft.world.level.ChunkPos(vasyan.blockPosition());
            ChunkForceTracker.ChunkKey key = new ChunkForceTracker.ChunkKey(serverLevel.dimension(), current);
            // A Vasyan reloaded from NBT after a chunk unload keeps the same UUID.
            // The chunk was left force-loaded on unload (issue #14), so reuse
            // that existing force instead of double-counting. Newcomers that do
            // not already hold the chunk force it normally.
            if (chunkForceTracker.hasHolder(serverLevel.dimension(), current, vasyan.getUUID())) {
                AgentDebugBuffer.log(vasyan.getVasyanName(), "CHUNK", "adopt existing [" + current.x + "," + current.z + "] in " + serverLevel.dimension().location());
                vasyan.setForcedChunk(key);
            } else {
                forceChunk(serverLevel, current, vasyan.getUUID());
                vasyan.setForcedChunk(key);
            }
        }
        return vasyan;
    }

    /**
     * Spawns a new Vasyan at the given position if no live Vasyan with the same
     * name (ignoring case) is already tracked or present in the level.
     *
     * @param level    the level to spawn in
     * @param position the spawn position
     * @param name     the desired Vasyan name
     * @return the spawned entity, or null if a duplicate exists or limits are reached
     */
    public VasyanEntity spawnVasyan(ServerLevel level, Vec3 position, String name) {
        name = requireNonNull(name, "Vasyan name must not be null");
        VasyanMod.LOGGER.info("Current active Vasyans: {}", activeVasyans.size());

        if (findByNameIgnoreCase(name) != null) {
            VasyanMod.LOGGER.warn("Vasyan name '{}' already exists", name);
            return null;
        }
        // Uniqueness check against the world itself: a bot with this name may
        // be loaded from a chunk but not tracked yet - adopt it instead of
        // spawning a duplicate.
        VasyanEntity existing = findVasyanInLevel(level, name);
        if (existing != null) {
            adopt(existing);
            VasyanMod.LOGGER.warn("Vasyan name '{}' already exists in world, adopting existing instance", name);
            return null;
        }

        int maxVasyans = VasyanConfig.MAX_ACTIVE_VASYANS.get();
        if (activeVasyans.size() >= maxVasyans) {
            VasyanMod.LOGGER.warn("Max Vasyan limit reached: {}", maxVasyans);
            return null;
        }

        VasyanEntity vasyan;
        try {
            VasyanMod.LOGGER.info("EntityType: {}", VasyanMod.VASYAN_ENTITY.get());
            vasyan = new VasyanEntity(VasyanMod.VASYAN_ENTITY.get(), level);
        } catch (Throwable e) {
            VasyanMod.LOGGER.error("Failed to create Vasyan entity", e);
            VasyanMod.LOGGER.error("Exception class: {}", e.getClass().getName());
            VasyanMod.LOGGER.error("Exception message: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }

        try {
            vasyan.setVasyanName(name);
            vasyan.setPos(position.x, position.y, position.z);
            boolean added = level.addFreshEntity(vasyan);
            if (added) {
                // Registration is done by adopt() via onEntityJoinLevel - do not
                // touch the registries here. Verify that adopt accepted this
                // exact instance; a same-named Vasyan may have been loaded
                // concurrently and won the dedup, in which case vasyan was
                // already discarded.
                if (findByNameIgnoreCase(name) == vasyan && vasyan.isAlive()) {
                    VasyanMod.LOGGER.info("Successfully spawned Vasyan: {} with UUID {} at {}", name, vasyan.getUUID(), position);
                    return vasyan;
                } else {
                    VasyanMod.LOGGER.warn("Spawn-adopt mismatch discard for '{}' ({} alive={}, removed={})",
                            name, vasyan.getUUID(), vasyan.isAlive(), vasyan.isRemoved());
                    if (!vasyan.isRemoved()) {
                        vasyan.setSuppressInventoryDrop(true);
                        vasyan.discard();
                    }
                }
            } else {
                VasyanMod.LOGGER.error("Failed to add Vasyan entity to world (addFreshEntity returned false)");
                VasyanMod.LOGGER.error("=== SPAWN ATTEMPT FAILED ===");
            }
        } catch (Throwable e) {
            VasyanMod.LOGGER.error("Exception during spawn setup", e);
            VasyanMod.LOGGER.error("=== SPAWN ATTEMPT FAILED WITH EXCEPTION ===");
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Scans the given level for a live VasyanEntity that carries this name but
     * is not necessarily tracked yet (e.g. loaded from a chunk). Used to stop
     * /vasyan spawn from creating a duplicate over an existing world instance.
     */
    private VasyanEntity findVasyanInLevel(ServerLevel level, String name) {
        if (level == null || name == null) {
            return null;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof VasyanEntity vasyan
                    && name.equalsIgnoreCase(vasyan.getVasyanName())
                    && vasyan.isAlive() && !vasyan.isRemoved()) {
                return vasyan;
            }
        }
        return null;
    }

    /**
     * Looks up a tracked Vasyan by name, ignoring case.
     *
     * @param name the Vasyan name to look up
     * @return the tracked entity, or null if no match is found
     */
    public VasyanEntity getVasyan(String name) {
        return name == null ? null : findByNameIgnoreCase(name);
    }

    /**
     * Looks up a tracked Vasyan by UUID.
     *
     * @param uuid the UUID to look up
     * @return the tracked entity, or null if no match is found
     */
    public VasyanEntity getVasyan(UUID uuid) {
        return uuid == null ? null : vasyansByUUID.get(uuid);
    }

    /**
     * Removes every live VasyanEntity with the given name from all server
     * levels and cleans up the registries. The world sweep is required
     * because a bot loaded from NBT may exist in a chunk without being
     * tracked in the maps (e.g. after the dedup auto-spawn regression).
     *
     * <p>Note: {@link ServerLevel#getAllEntities()} only enumerates entities
     * in <em>loaded</em> chunks. A bot whose chunk is unloaded is not visited
     * by the sweep and will be re-adopted (and thus re-appear in the
     * registry) via {@link #adopt(VasyanEntity)} when its chunk loads again;
     * run this command again after the chunk is loaded to remove it.
     *
     * <p>When several same-named instances exist in the world (a bug), only
     * the tracked instance may drop its inventory; every other duplicate has
     * its inventory drop suppressed so the (identical, duplicated) contents
     * are not dropped multiple times - an item-dupe exploit.
     */
    public boolean removeVasyan(String name, MinecraftServer server) {
        if (name == null) {
            return false;
        }
        boolean removed = false;
        if (server != null) {
            // Collect first, discard after: iterating getAllEntities() while
            // removing entities from it would throw a concurrent-modification.
            List<VasyanEntity> matches = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof VasyanEntity vasyan && name.equalsIgnoreCase(vasyan.getVasyanName())) {
                        matches.add(vasyan);
                    }
                }
            }
            VasyanEntity trackedInWorldSweep = findByNameIgnoreCase(name);
            for (VasyanEntity vasyan : matches) {
                if (!vasyan.isAlive() || vasyan.isRemoved()) {
                    continue;
                }
                if (vasyan != trackedInWorldSweep) {
                    // Same-named duplicate: dropping its (identical) contents
                    // would dupe items, so suppress the drop for non-tracked
                    // copies. The tracked instance keeps the normal drop.
                    vasyan.setSuppressInventoryDrop(true);
                }
                VasyanMod.LOGGER.warn("removeVasyan discard for '{}' ({} tracked={})", name, vasyan.getUUID(), vasyan == trackedInWorldSweep);
                vasyan.discard();
                removed = true;
            }
        }
        Map.Entry<String, VasyanEntity> trackedEntry = findEntryByNameIgnoreCase(name);
        if (trackedEntry != null) {
            activeVasyans.remove(trackedEntry.getKey());
            vasyansByUUID.remove(trackedEntry.getValue().getUUID());
            removed = true;
        }
        return removed;
    }

    /**
     * Cleans up the registries when a tracked Vasyan leaves the world for a
     * reason other than a dimension change: chunk unload, kill or discard.
     * The bot is re-adopted via {@link #adopt(VasyanEntity)} when its chunk
     * loads again. A dimension change keeps the registration: the same live
     * instance continues to exist and is re-adopted (idempotently) on join.
     */
    public void onVasyanUnload(VasyanEntity vasyan) {
        if (vasyan == null) {
            return;
        }
        String name = requireNonNull(vasyan.getVasyanName(), "Vasyan name must not be null");
        Map.Entry<String, VasyanEntity> trackedEntry = findEntryByNameIgnoreCase(name);
        if (trackedEntry != null && trackedEntry.getValue() == vasyan) {
            activeVasyans.remove(trackedEntry.getKey());
            VasyanMod.LOGGER.info("Vasyan '{}' left the world (reason={}), removed from registry", name, vasyan.getRemovalReason());
        }
        vasyansByUUID.remove(vasyan.getUUID());
    }

    /**
     * Periodic registry cleanup: drops entries whose entity is dead or
     * removed. Safety net for removal reasons that do not go through
     * {@link #onVasyanUnload(VasyanEntity)}.
     */
    public void tick() {
        Iterator<Map.Entry<String, VasyanEntity>> iterator = activeVasyans.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VasyanEntity> entry = iterator.next();
            VasyanEntity vasyan = entry.getValue();
            if (!vasyan.isAlive() || vasyan.isRemoved()) {
                iterator.remove();
                vasyansByUUID.remove(vasyan.getUUID());
                VasyanMod.LOGGER.info("Cleaned up Vasyan: {}", entry.getKey());
            }
        }
    }

    public void clearAllVasyans() {
        VasyanMod.LOGGER.info("Clearing {} Vasyan entities", activeVasyans.size());
        for (VasyanEntity vasyan : activeVasyans.values()) {
            VasyanMod.LOGGER.warn("clearAllVasyans discard for '{}' ({})", vasyan.getVasyanName(), vasyan.getUUID());
            vasyan.discard();
        }
        activeVasyans.clear();
        vasyansByUUID.clear();
    }

    public Collection<VasyanEntity> getAllVasyans() {
        return Collections.unmodifiableCollection(activeVasyans.values());
    }

    public List<String> getVasyanNames() {
        return new ArrayList<>(activeVasyans.keySet());
    }

    public int getActiveCount() {
        return activeVasyans.size();
    }

    // ---- chunk force-loading (work without players) ----

    private final ChunkForceTracker chunkForceTracker = new ChunkForceTracker();

    /** Package-private accessor for tests. */
    ChunkForceTracker getChunkForceTracker() {
        return chunkForceTracker;
    }

    /** Force-loads a chunk for a specific Vasyan (refcounted across Vasyans). */
    public void forceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos, UUID uuid) {
        boolean newlyLoaded = chunkForceTracker.force(level.dimension(), chunkPos, uuid);
        if (newlyLoaded) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
        }
        String action = newlyLoaded ? "force" : "add-holder";
        AgentDebugBuffer.log("system", "CHUNK", action + " [" + chunkPos.x + "," + chunkPos.z + "] in " + level.dimension().location() + " (holders: " + chunkForceTracker.holders(level.dimension(), chunkPos) + ")");
    }

    /** Releases a chunk force-load for a specific Vasyan; un-forces when the last holder leaves. */
    public void unforceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos, UUID uuid) {
        boolean fullyUnforced = chunkForceTracker.unforce(level.dimension(), chunkPos, uuid);
        if (fullyUnforced) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        String action = fullyUnforced ? "unforce" : "remove-holder";
        AgentDebugBuffer.log("system", "CHUNK", action + " [" + chunkPos.x + "," + chunkPos.z + "] in " + level.dimension().location() + " (holders: " + chunkForceTracker.holders(level.dimension(), chunkPos) + ")");
    }

    /**
     * Drops a Vasyan's chunk force-load (on removal). The refcount keeps other
     * Vasyans in the same chunk unaffected. Un-forces in the dimension the
     * chunk actually belongs to (the Vasyan may have changed dimensions).
     */
    public void releaseChunk(VasyanEntity vasyan, ServerLevel level) {
        ChunkForceTracker.ChunkKey chunkKey = vasyan.getForcedChunk();
        if (chunkKey != null) {
            AgentDebugBuffer.log(vasyan.getVasyanName(), "CHUNK", "release [" + chunkKey.pos().x + "," + chunkKey.pos().z + "] in " + chunkKey.dimension().location());
            ServerLevel ownerLevel = level.getServer() != null
                ? level.getServer().getLevel(chunkKey.dimension())
                : null;
            if (ownerLevel != null) {
                unforceChunk(ownerLevel, chunkKey.pos(), vasyan.getUUID());
            }
            vasyan.setForcedChunk(null);
        }
    }

    /**
     * Keeps every tracked Vasyan's current chunk force-loaded. Runs from the
     * server tick event (NOT from the entity tick): an entity in an unloaded
     * chunk never ticks, so it could never force its own chunk - the
     * manager is the outside actor that breaks the deadlock.
     */
    private void updateForcedChunks(ServerLevel level) {
        if (!VasyanConfig.FORCE_LOAD_CHUNKS.get()) {
            // Feature disabled at runtime: release everything we ever forced
            // so no chunk stays force-loaded forever.
            for (VasyanEntity vasyan : activeVasyans.values()) {
                ChunkForceTracker.ChunkKey old = vasyan.getForcedChunk();
                if (old != null) {
                    ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                    if (ownerLevel != null) {
                        unforceChunk(ownerLevel, old.pos(), vasyan.getUUID());
                    }
                    vasyan.setForcedChunk(null);
                }
            }
            return;
        }
        for (VasyanEntity vasyan : activeVasyans.values()) {
            if (vasyan.level() != level) {
                continue;
            }
            ChunkForceTracker.ChunkKey current = new ChunkForceTracker.ChunkKey(
                level.dimension(), new net.minecraft.world.level.ChunkPos(vasyan.blockPosition()));
            ChunkForceTracker.ChunkKey old = vasyan.getForcedChunk();
            if (current.equals(old)) {
                continue;
            }
            forceChunk(level, current.pos(), vasyan.getUUID());
            AgentDebugBuffer.log(vasyan.getVasyanName(), "CHUNK", "force current [" + current.pos().x + "," + current.pos().z + "]");
            if (old != null) {
                // Un-force the previous chunk in ITS dimension (dimension change)
                ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                if (ownerLevel != null) {
                    unforceChunk(ownerLevel, old.pos(), vasyan.getUUID());
                }
            }
            vasyan.setForcedChunk(current);
        }
    }

    public void tick(ServerLevel level) {
        updateForcedChunks(level);

        // Clean up dead or removed Vasyans
        Iterator<Map.Entry<String, VasyanEntity>> iterator = activeVasyans.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VasyanEntity> entry = iterator.next();
            VasyanEntity vasyan = entry.getValue();
            if (!vasyan.isAlive() || vasyan.isRemoved()) {
                releaseChunk(vasyan, level);
                iterator.remove();
                vasyansByUUID.remove(vasyan.getUUID());
                VasyanMod.LOGGER.info("Removed dead Vasyan: {}", entry.getKey());
            }
        }
    }
}
