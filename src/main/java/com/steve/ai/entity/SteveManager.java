package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

public class SteveManager {
    private final Map<String, SteveEntity> activeSteves;
    private final Map<UUID, SteveEntity> stevesByUUID;

    public SteveManager() {
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
    private Map.Entry<String, SteveEntity> findEntryByNameIgnoreCase(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, SteveEntity> entry : activeSteves.entrySet()) {
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
    private SteveEntity findByNameIgnoreCase(String name) {
        Map.Entry<String, SteveEntity> entry = findEntryByNameIgnoreCase(name);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * Registers a SteveEntity that entered the world (fresh spawn or loaded
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
    public SteveEntity adopt(SteveEntity steve) {
        if (steve == null) {
            return null;
        }
        String name = requireNonNull(steve.getSteveName(), "Steve name must not be null");
        Map.Entry<String, SteveEntity> existingEntry = findEntryByNameIgnoreCase(name);
        SteveEntity existing = existingEntry != null ? existingEntry.getValue() : null;
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
                existing.setSuppressInventoryDrop(true);
                existing.discard();
                SteveMod.LOGGER.info("Dedup: replaced fresh duplicate '{}' ({}) with NBT-loaded original ({})",
                        name, existing.getUUID(), steve.getUUID());
                activeSteves.remove(existingKey);
                stevesByUUID.remove(existing.getUUID());
            } else {
                // Duplicate bot with the same name: reject the newcomer. It has
                // not entered the world yet during an EntityJoinLevelEvent, so
                // the join is canceled (see ServerEventHandler) instead of a
                // discard that would drop the (identical, duplicated) contents.
                SteveMod.LOGGER.info("Dedup: rejected duplicate Steve '{}' ({}) - another live instance exists",
                        name, steve.getUUID());
                return null;
            }
        }
        activeSteves.put(name, steve);
        stevesByUUID.put(steve.getUUID(), steve);
        if (SteveConfig.FORCE_LOAD_CHUNKS.get()
                && steve.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.ChunkPos current = new net.minecraft.world.level.ChunkPos(steve.blockPosition());
            forceChunk(serverLevel, current);
            steve.setForcedChunk(new ChunkForceTracker.ChunkKey(serverLevel.dimension(), current));
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
    public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
        name = requireNonNull(name, "Steve name must not be null");
        SteveMod.LOGGER.info("Current active Steves: {}", activeSteves.size());

        if (findByNameIgnoreCase(name) != null) {
            SteveMod.LOGGER.warn("Steve name '{}' already exists", name);
            return null;
        }
        // Uniqueness check against the world itself: a bot with this name may
        // be loaded from a chunk but not tracked yet - adopt it instead of
        // spawning a duplicate.
        SteveEntity existing = findSteveInLevel(level, name);
        if (existing != null) {
            adopt(existing);
            SteveMod.LOGGER.warn("Steve name '{}' already exists in world, adopting existing instance", name);
            return null;
        }

        int maxSteves = SteveConfig.MAX_ACTIVE_STEVES.get();
        if (activeSteves.size() >= maxSteves) {
            SteveMod.LOGGER.warn("Max Steve limit reached: {}", maxSteves);
            return null;
        }

        SteveEntity steve;
        try {
            SteveMod.LOGGER.info("EntityType: {}", SteveMod.STEVE_ENTITY.get());
            steve = new SteveEntity(SteveMod.STEVE_ENTITY.get(), level);
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Failed to create Steve entity", e);
            SteveMod.LOGGER.error("Exception class: {}", e.getClass().getName());
            SteveMod.LOGGER.error("Exception message: {}", e.getMessage());
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
                    SteveMod.LOGGER.info("Successfully spawned Steve: {} with UUID {} at {}", name, steve.getUUID(), position);
                    return steve;
                } else {
                    SteveMod.LOGGER.warn("Steve '{}' was added to the world but adopt() did not register it - discarding", name);
                    if (!steve.isRemoved()) {
                        steve.setSuppressInventoryDrop(true);
                        steve.discard();
                    }
                }
            } else {
                SteveMod.LOGGER.error("Failed to add Steve entity to world (addFreshEntity returned false)");
                SteveMod.LOGGER.error("=== SPAWN ATTEMPT FAILED ===");
            }
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Exception during spawn setup", e);
            SteveMod.LOGGER.error("=== SPAWN ATTEMPT FAILED WITH EXCEPTION ===");
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Scans the given level for a live SteveEntity that carries this name but
     * is not necessarily tracked yet (e.g. loaded from a chunk). Used to stop
     * /steve spawn from creating a duplicate over an existing world instance.
     */
    private SteveEntity findSteveInLevel(ServerLevel level, String name) {
        if (level == null || name == null) {
            return null;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SteveEntity steve
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
    public SteveEntity getSteve(String name) {
        return name == null ? null : findByNameIgnoreCase(name);
    }

    /**
     * Looks up a tracked Steve by UUID.
     *
     * @param uuid the UUID to look up
     * @return the tracked entity, or null if no match is found
     */
    public SteveEntity getSteve(UUID uuid) {
        return uuid == null ? null : stevesByUUID.get(uuid);
    }

    /**
     * Removes every live SteveEntity with the given name from all server
     * levels and cleans up the registries. The world sweep is required
     * because a bot loaded from NBT may exist in a chunk without being
     * tracked in the maps (e.g. after the dedup auto-spawn regression).
     *
     * <p>Note: {@link ServerLevel#getAllEntities()} only enumerates entities
     * in <em>loaded</em> chunks. A bot whose chunk is unloaded is not visited
     * by the sweep and will be re-adopted (and thus re-appear in the
     * registry) via {@link #adopt(SteveEntity)} when its chunk loads again;
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
            List<SteveEntity> matches = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof SteveEntity steve && name.equalsIgnoreCase(steve.getSteveName())) {
                        matches.add(steve);
                    }
                }
            }
            SteveEntity trackedInWorldSweep = findByNameIgnoreCase(name);
            for (SteveEntity steve : matches) {
                if (!steve.isAlive() || steve.isRemoved()) {
                    continue;
                }
                if (steve != trackedInWorldSweep) {
                    // Same-named duplicate: dropping its (identical) contents
                    // would dupe items, so suppress the drop for non-tracked
                    // copies. The tracked instance keeps the normal drop.
                    steve.setSuppressInventoryDrop(true);
                }
                steve.discard();
                removed = true;
            }
        }
        Map.Entry<String, SteveEntity> trackedEntry = findEntryByNameIgnoreCase(name);
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
     * The bot is re-adopted via {@link #adopt(SteveEntity)} when its chunk
     * loads again. A dimension change keeps the registration: the same live
     * instance continues to exist and is re-adopted (idempotently) on join.
     */
    public void onSteveUnload(SteveEntity steve) {
        if (steve == null) {
            return;
        }
        String name = requireNonNull(steve.getSteveName(), "Steve name must not be null");
        Map.Entry<String, SteveEntity> trackedEntry = findEntryByNameIgnoreCase(name);
        if (trackedEntry != null && trackedEntry.getValue() == steve) {
            activeSteves.remove(trackedEntry.getKey());
            SteveMod.LOGGER.info("Steve '{}' left the world, removed from registry", name);
        }
        stevesByUUID.remove(steve.getUUID());
    }

    /**
     * Periodic registry cleanup: drops entries whose entity is dead or
     * removed. Safety net for removal reasons that do not go through
     * {@link #onSteveUnload(SteveEntity)}.
     */
    public void tick() {
        Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SteveEntity> entry = iterator.next();
            SteveEntity steve = entry.getValue();
            if (!steve.isAlive() || steve.isRemoved()) {
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                SteveMod.LOGGER.info("Cleaned up Steve: {}", entry.getKey());
            }
        }
    }

    public void clearAllSteves() {
        SteveMod.LOGGER.info("Clearing {} Steve entities", activeSteves.size());
        for (SteveEntity steve : activeSteves.values()) {
            steve.discard();
        }
        activeSteves.clear();
        stevesByUUID.clear();
    }

    public Collection<SteveEntity> getAllSteves() {
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

    /** Force-loads a chunk while a Steve is in it (refcounted across Steves). */
    public void forceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos) {
        if (chunkForceTracker.force(level.dimension(), chunkPos)) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
        }
    }

    /** Releases a chunk force-load; the chunk unloads when the last Steve leaves. */
    public void unforceChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos) {
        if (chunkForceTracker.unforce(level.dimension(), chunkPos)) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
    }

    /**
     * Drops a Steve's chunk force-load (on removal). The refcount keeps other
     * Steves in the same chunk unaffected. Un-forces in the dimension the
     * chunk actually belongs to (the Steve may have changed dimensions).
     */
    public void releaseChunk(SteveEntity steve, ServerLevel level) {
        ChunkForceTracker.ChunkKey chunkKey = steve.getForcedChunk();
        if (chunkKey != null) {
            ServerLevel ownerLevel = level.getServer() != null
                ? level.getServer().getLevel(chunkKey.dimension())
                : null;
            if (ownerLevel != null) {
                unforceChunk(ownerLevel, chunkKey.pos());
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
        if (!SteveConfig.FORCE_LOAD_CHUNKS.get()) {
            // Feature disabled at runtime: release everything we ever forced
            // so no chunk stays force-loaded forever.
            for (SteveEntity steve : activeSteves.values()) {
                ChunkForceTracker.ChunkKey old = steve.getForcedChunk();
                if (old != null) {
                    ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                    if (ownerLevel != null) {
                        unforceChunk(ownerLevel, old.pos());
                    }
                    steve.setForcedChunk(null);
                }
            }
            return;
        }
        for (SteveEntity steve : activeSteves.values()) {
            if (steve.level() != level) {
                continue;
            }
            ChunkForceTracker.ChunkKey current = new ChunkForceTracker.ChunkKey(
                level.dimension(), new net.minecraft.world.level.ChunkPos(steve.blockPosition()));
            ChunkForceTracker.ChunkKey old = steve.getForcedChunk();
            if (current.equals(old)) {
                continue;
            }
            forceChunk(level, current.pos());
            if (old != null) {
                // Un-force the previous chunk in ITS dimension (dimension change)
                ServerLevel ownerLevel = level.getServer().getLevel(old.dimension());
                if (ownerLevel != null) {
                    unforceChunk(ownerLevel, old.pos());
                }
            }
            steve.setForcedChunk(current);
        }
    }

    public void tick(ServerLevel level) {
        updateForcedChunks(level);

        // Clean up dead or removed Steves
        Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SteveEntity> entry = iterator.next();
            SteveEntity steve = entry.getValue();
            if (!steve.isAlive() || steve.isRemoved()) {
                releaseChunk(steve, level);
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                SteveMod.LOGGER.info("Removed dead Steve: {}", entry.getKey());
            }
        }
    }
}
