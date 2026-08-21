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
    private final Map<String, VasyanEntity> activeSteves;
    private final Map<UUID, VasyanEntity> stevesByUUID;

    public VasyanManager() {
        this.activeSteves = new ConcurrentHashMap<>();
        this.stevesByUUID = new ConcurrentHashMap<>();
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
        for (Map.Entry<String, VasyanEntity> entry : activeSteves.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns the tracked Steve whose canonical name matches the given name
     * ignoring case.
     *
     * @param name the name to look up, may be null
     * @return the matching entity, or null if no Steve is tracked under a
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
     * {@link #removeSteve(String, MinecraftServer)}.
     *
     * @return the adopted instance, or null if it was rejected as a duplicate
     */
    public VasyanEntity adopt(VasyanEntity steve) {
        if (steve == null) {
            return null;
        }
        String name = requireNonNull(steve.getSteveName(), "Steve name must not be null");
        Map.Entry<String, VasyanEntity> existingEntry = findEntryByNameIgnoreCase(name);
        VasyanEntity existing = existingEntry != null ? existingEntry.getValue() : null;
        if (existing != null) {
            if (existing == steve) {
                return steve;
            }
            String existingKey = existingEntry.getKey();
            if (!existing.isAlive() || existing.isRemoved()) {
                // Stale registry entry (e.g. survivor of a crash) - replace it
                activeSteves.remove(existingKey);
                stevesByUUID.remove(existing.getUUID());
            } else if (steve.isLoadedFromNbt() && !existing.isLoadedFromNbt()) {
                // The newcomer is the real bot loaded from NBT; the survivor is
                // a freshly spawned empty copy. Keep the NBT state, discard the
                // empty copy without dropping its (empty) inventory.
                VasyanMod.LOGGER.warn("Dedup discard: replacing fresh '{}' ({} alive={}, removed={}) with NBT-loaded original ({})",
                        name, existing.getUUID(), existing.isAlive(), existing.isRemoved(), steve.getUUID());
                existing.setSuppressInventoryDrop(true);
                existing.discard();
                VasyanMod.LOGGER.info("Dedup: replaced fresh duplicate '{}' ({}) with NBT-loaded original ({})",
                        name, existing.getUUID(), steve.getUUID());
                activeSteves.remove(existingKey);
                stevesByUUID.remove(existing.getUUID());
            } else {
                // Duplicate bot with the same name: reject the newcomer. It has
                // not entered the world yet during an EntityJoinLevelEvent, so
                // the join is canceled (see ServerEventHandler) instead of a
                // discard that would drop the (identical, duplicated) contents.
                VasyanMod.LOGGER.info("Dedup: rejected duplicate Steve '{}' ({}) - another live instance exists",
                        name, steve.getUUID());
                return null;
            }
        }
        activeSteves.put(name, steve);
        stevesByUUID.put(steve.getUUID(), steve);
        if (VasyanConfig.FORCE_LOAD_CHUNKS.get()
                && steve.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.ChunkPos current = new net.minecraft.world.level.ChunkPos(steve.blockPosition());
            ChunkForceTracker.ChunkKey key = new ChunkForceTracker.ChunkKey(serverLevel.dimension(), current);
            // A Steve reloaded from NBT after a chunk unload keeps the same UUID.
            // The chunk was left force-loaded on unload (issue #14), so reuse
            // that existing force instead of double-counting. Newcomers that do
            // not already hold the chunk force it normally.
            if (chunkForceTracker.hasHolder(serverLevel.dimension(), current, steve.getUUID())) {
                AgentDebugBuffer.log(steve.getSteveName(), "CHUNK", "adopt existing [" + current.x + "," + current.z + "] in " + serverLevel.dimension().location());
                steve.setForcedChunk(key);
            } else {
                forceChunk(serverLevel, current, steve.getUUID());
                steve.setForcedChunk(key);
            }
        }
        return steve;
    }

    /**
     * Spawns a new Steve at the given position if no live Steve with the same
     * name (ignoring case) is already tracked or present in the level.
     *
     * @param level    the level to spawn in
     * @param position the spawn position
     * @param name     the desired Steve name
     * @return the spawned entity, or null if a duplicate exists or limits are reached
     */
    public VasyanEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
        name = requireNonNull(name, "Steve name must not be null");
        VasyanMod.LOGGER.info("Current active Steves: {}", activeSteves.size());

        if (findByNameIgnoreCase(name) != null) {
            VasyanMod.LOGGER.warn("Steve name '{}' already exists", name);
            return null;
        }
        // Uniqueness check against the world itself: a bot with this name may
        // be loaded from a chunk but not tracked yet - adopt it instead of
        // spawning a duplicate.
        VasyanEntity existing = findSteveInLevel(level, name);
        if (existing != null) {
            adopt(existing);
            VasyanMod.LOGGER.warn("Steve name '{}' already exists in world, adopting existing instance", name);
            return null;
        }

        int maxSteves = VasyanConfig.MAX_ACTIVE_STEVES.get();
        if (activeSteves.size() >= maxSteves) {
            VasyanMod.LOGGER.warn("Max Steve limit reached: {}", maxSteves);
            return null;
        }

        VasyanEntity steve;
        try {
            VasyanMod.LOGGER.info("EntityType: {}", VasyanMod.VASYAN_ENTITY.get());
            steve = new VasyanEntity(VasyanMod.VASYAN_ENTITY.get(), level);
        } catch (Throwable e) {
            VasyanMod.LOGGER.error("Failed to create Steve entity", e);
            VasyanMod.LOGGER.error("Exception class: {}", e.getClass().getName());
            VasyanMod.LOGGER.error("Exception message: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }

        try {
            steve.setSteveName(name);
            steve.setPos(position.x, position.y, position.z);
            boolean added = level.addFreshEntity(steve);
            if (added) {
                // Registration is done by adopt() via onEntityJoinLevel - do not
                // touch the registries here. Verify that adopt accepted this
                // exact instance; a same-named Steve may have been loaded
                // concurrently and won the dedup, in which case steve was
                // already discarded.
                if (findByNameIgnoreCase(name) == steve && steve.isAlive()) {
                    VasyanMod.LOGGER.info("Successfully spawned Steve: {} with UUID {} at {}", name, steve.getUUID(), position);
                    return steve;
                } else {
                    VasyanMod.LOGGER.warn("Spawn-adopt mismatch discard for '{}' ({} alive={}, removed={})",
                            name, steve.getUUID(), steve.isAlive(), steve.isRemoved());
                    if (!steve.isRemoved()) {
                        steve.setSuppressInventoryDrop(true);
                        steve.discard();
                    }
                }
            } else {
                VasyanMod.LOGGER.error("Failed to add Steve entity to world (addFreshEntity returned false)");
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
     * /steve spawn from creating a duplicate over an existing world instance.
     */
    private VasyanEntity findSteveInLevel(ServerLevel level, String name) {
        if (level == null || name == null) {
            return null;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof VasyanEntity steve
                    && name.equalsIgnoreCase(steve.getSteveName())
                    && steve.isAlive() && !steve.isRemoved()) {
                return steve;
            }
        }
        return null;
    }

    /**
     * Looks up a tracked Steve by name, ignoring case.
     *
     * @param name the Steve name to look up
     * @return the tracked entity, or null if no match is found
     */
    public VasyanEntity getSteve(String name) {
        return name == null ? null : findByNameIgnoreCase(name);
    }

    /**
     * Looks up a tracked Steve by UUID.
     *
     * @param uuid the UUID to look up
     * @return the tracked entity, or null if no match is found
     */
    public VasyanEntity getSteve(UUID uuid) {
        return uuid == null ? null : stevesByUUID.get(uuid);
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
    public boolean removeSteve(String name, MinecraftServer server) {
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
                    if (entity instanceof VasyanEntity steve && name.equalsIgnoreCase(steve.getSteveName())) {
                        matches.add(steve);
                    }
                }
            }
            VasyanEntity trackedInWorldSweep = findByNameIgnoreCase(name);
            for (VasyanEntity steve : matches) {
                if (!steve.isAlive() || steve.isRemoved()) {
                    continue;
                }
                if (steve != trackedInWorldSweep) {
                    // Same-named duplicate: dropping its (identical) contents
                    // would dupe items, so suppress the drop for non-tracked
                    // copies. The tracked instance keeps the normal drop.
                    steve.setSuppressInventoryDrop(true);
                }
                VasyanMod.LOGGER.warn("removeSteve discard for '{}' ({} tracked={})", name, steve.getUUID(), steve == trackedInWorldSweep);
                steve.discard();
                removed = true;
            }
        }
        Map.Entry<String, VasyanEntity> trackedEntry = findEntryByNameIgnoreCase(name);
        if (trackedEntry != null) {
            activeSteves.remove(trackedEntry.getKey());
            stevesByUUID.remove(trackedEntry.getValue().getUUID());
            removed = true;
        }
        return removed;
    }

    /**
     * Cleans up the registries when a tracked Steve leaves the world for a
     * reason other than a dimension change: chunk unload, kill or discard.
     * The bot is re-adopted via {@link #adopt(VasyanEntity)} when its chunk
     * loads again. A dimension change keeps the registration: the same live
     * instance continues to exist and is re-adopted (idempotently) on join.
     */
    public void onSteveUnload(VasyanEntity steve) {
        if (steve == null) {
            return;
        }
        String name = requireNonNull(steve.getSteveName(), "Steve name must not be null");
        Map.Entry<String, VasyanEntity> trackedEntry = findEntryByNameIgnoreCase(name);
        if (trackedEntry != null && trackedEntry.getValue() == steve) {
            activeSteves.remove(trackedEntry.getKey());
            VasyanMod.LOGGER.info("Steve '{}' left the world (reason={}), removed from registry", name, steve.getRemovalReason());
        }
        stevesByUUID.remove(steve.getUUID());
    }

    /**
     * Periodic registry cleanup: drops entries whose entity is dead or
     * removed. Safety net for removal reasons that do not go through
     * {@link #onSteveUnload(VasyanEntity)}.
     */
    public void tick() {
        Iterator<Map.Entry<String, VasyanEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VasyanEntity> entry = iterator.next();
            VasyanEntity steve = entry.getValue();
            if (!steve.isAlive() || steve.isRemoved()) {
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                VasyanMod.LOGGER.info("Cleaned up Steve: {}", entry.getKey());
            }
        }
    }

    public void clearAllSteves() {
        VasyanMod.LOGGER.info("Clearing {} Steve entities", activeSteves.size());
        for (VasyanEntity steve : activeSteves.values()) {
            VasyanMod.LOGGER.warn("clearAllSteves discard for '{}' ({})", steve.getSteveName(), steve.getUUID());
            steve.discard();
        }
        activeSteves.clear();
        stevesByUUID.clear();
    }

    public Collection<VasyanEntity> getAllSteves() {
        return Collections.unmodifiableCollection(activeSteves.values());
    }

    public List<String> getSteveNames() {
        return new ArrayList<>(activeSteves.keySet());
    }

    public int getActiveCount() {
        return activeSteves.size();
    }

    // ---- chunk force-loading (work without players) ----

    private final ChunkForceTracker chunkForceTracker = new ChunkForceTracker();

    /** Package-private accessor for tests. */
    ChunkForceTracker getChunkForceTracker() {
        return chunkForceTracker;
    }

    /** Force-loads a chunk for a specific Steve (refcounted across Steves). */
    public void forceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos, UUID uuid) {
        boolean newlyLoaded = chunkForceTracker.force(level.dimension(), chunkPos, uuid);
        if (newlyLoaded) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
        }
        String action = newlyLoaded ? "force" : "add-holder";
        AgentDebugBuffer.log("system", "CHUNK", action + " [" + chunkPos.x + "," + chunkPos.z + "] in " + level.dimension().location() + " (holders: " + chunkForceTracker.holders(level.dimension(), chunkPos) + ")");
    }

    /** Releases a chunk force-load for a specific Steve; un-forces when the last holder leaves. */
    public void unforceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos, UUID uuid) {
        boolean fullyUnforced = chunkForceTracker.unforce(level.dimension(), chunkPos, uuid);
        if (fullyUnforced) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        String action = fullyUnforced ? "unforce" : "remove-holder";
        AgentDebugBuffer.log("system", "CHUNK", action + " [" + chunkPos.x + "," + chunkPos.z + "] in " + level.dimension().location() + " (holders: " + chunkForceTracker.holders(level.dimension(), chunkPos) + ")");
    }

    /**
     * Drops a Steve's chunk force-load (on removal). The refcount keeps other
     * Steves in the same chunk unaffected. Un-forces in the dimension the
     * chunk actually belongs to (the Steve may have changed dimensions).
     */
    public void releaseChunk(VasyanEntity steve, ServerLevel level) {
        ChunkForceTracker.ChunkKey chunkKey = steve.getForcedChunk();
        if (chunkKey != null) {
            AgentDebugBuffer.log(steve.getSteveName(), "CHUNK", "release [" + chunkKey.pos().x + "," + chunkKey.pos().z + "] in " + chunkKey.dimension().location());
            ServerLevel ownerLevel = level.getServer() != null
                ? level.getServer().getLevel(chunkKey.dimension())
                : null;
            if (ownerLevel != null) {
                unforceChunk(ownerLevel, chunkKey.pos(), steve.getUUID());
            }
            steve.setForcedChunk(null);
        }
    }

    /**
     * Keeps every tracked Steve's current chunk force-loaded. Runs from the
     * server tick event (NOT from the entity tick): an entity in an unloaded
     * chunk never ticks, so it could never force its own chunk - the
     * manager is the outside actor that breaks the deadlock.
     */
    private void updateForcedChunks(ServerLevel level) {
        if (!VasyanConfig.FORCE_LOAD_CHUNKS.get()) {
            // Feature disabled at runtime: release everything we ever forced
            // so no chunk stays force-loaded forever.
            for (VasyanEntity steve : activeSteves.values()) {
                ChunkForceTracker.ChunkKey old = steve.getForcedChunk();
                if (old != null) {
                    ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                    if (ownerLevel != null) {
                        unforceChunk(ownerLevel, old.pos(), steve.getUUID());
                    }
                    steve.setForcedChunk(null);
                }
            }
            return;
        }
        for (VasyanEntity steve : activeSteves.values()) {
            if (steve.level() != level) {
                continue;
            }
            ChunkForceTracker.ChunkKey current = new ChunkForceTracker.ChunkKey(
                level.dimension(), new net.minecraft.world.level.ChunkPos(steve.blockPosition()));
            ChunkForceTracker.ChunkKey old = steve.getForcedChunk();
            if (current.equals(old)) {
                continue;
            }
            forceChunk(level, current.pos(), steve.getUUID());
            AgentDebugBuffer.log(steve.getSteveName(), "CHUNK", "force current [" + current.pos().x + "," + current.pos().z + "]");
            if (old != null) {
                // Un-force the previous chunk in ITS dimension (dimension change)
                ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                if (ownerLevel != null) {
                    unforceChunk(ownerLevel, old.pos(), steve.getUUID());
                }
            }
            steve.setForcedChunk(current);
        }
    }

    public void tick(ServerLevel level) {
        updateForcedChunks(level);

        // Clean up dead or removed Steves
        Iterator<Map.Entry<String, VasyanEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VasyanEntity> entry = iterator.next();
            VasyanEntity steve = entry.getValue();
            if (!steve.isAlive() || steve.isRemoved()) {
                releaseChunk(steve, level);
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                VasyanMod.LOGGER.info("Removed dead Steve: {}", entry.getKey());
            }
        }
    }
}
