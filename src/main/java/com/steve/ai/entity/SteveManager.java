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

public class SteveManager {
    private final Map<String, SteveEntity> activeSteves;
    private final Map<UUID, SteveEntity> stevesByUUID;

    public SteveManager() {
        this.activeSteves = new ConcurrentHashMap<>();
        this.stevesByUUID = new ConcurrentHashMap<>();
    }

    /**
     * Registers a SteveEntity that entered the world (fresh spawn or loaded
     * from a chunk / NBT). If the name is already taken by another live
     * instance, the newcomer is a duplicate and gets discarded (dedup):
     * the first registered instance wins. Re-adopting the same instance is
     * a no-op (idempotent).
     *
     * @return the adopted instance, or null if it was discarded as a duplicate
     */
    public SteveEntity adopt(SteveEntity steve) {
        if (steve == null) {
            return null;
        }
        String name = steve.getSteveName();
        SteveEntity existing = activeSteves.get(name);
        if (existing != null) {
            if (existing == steve) {
                return steve;
            }
            if (!existing.isAlive() || existing.isRemoved()) {
                // Stale registry entry (e.g. survivor of a crash) - replace it
                activeSteves.remove(name);
                stevesByUUID.remove(existing.getUUID());
            } else {
                // Duplicate bot with the same name: discard the newcomer. Its
                // NBT-inventory equals the survivor's, so suppress the drop to
                // avoid an item-dupe exploit.
                steve.setSuppressInventoryDrop(true);
                steve.discard();
                SteveMod.LOGGER.info("Dedup: discarded duplicate Steve '{}' ({}) - another live instance exists",
                        name, steve.getUUID());
                return null;
            }
        }
        activeSteves.put(name, steve);
        stevesByUUID.put(steve.getUUID(), steve);
        return steve;
    }

    public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {        SteveMod.LOGGER.info("Current active Steves: {}", activeSteves.size());
        
        if (activeSteves.containsKey(name)) {
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
        }        int maxSteves = SteveConfig.MAX_ACTIVE_STEVES.get();        if (activeSteves.size() >= maxSteves) {
            SteveMod.LOGGER.warn("Max Steve limit reached: {}", maxSteves);
            return null;
        }        SteveEntity steve;
        try {            SteveMod.LOGGER.info("EntityType: {}", SteveMod.STEVE_ENTITY.get());
            steve = new SteveEntity(SteveMod.STEVE_ENTITY.get(), level);        } catch (Throwable e) {
            SteveMod.LOGGER.error("Failed to create Steve entity", e);
            SteveMod.LOGGER.error("Exception class: {}", e.getClass().getName());
            SteveMod.LOGGER.error("Exception message: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }

        try {            steve.setSteveName(name);            steve.setPos(position.x, position.y, position.z);            boolean added = level.addFreshEntity(steve);            if (added) {
                activeSteves.put(name, steve);
                stevesByUUID.put(steve.getUUID(), steve);
                SteveMod.LOGGER.info("Successfully spawned Steve: {} with UUID {} at {}", name, steve.getUUID(), position);                return steve;
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
        if (level == null) {
            return null;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SteveEntity steve
                    && name.equals(steve.getSteveName())
                    && steve.isAlive() && !steve.isRemoved()) {
                return steve;
            }
        }
        return null;
    }

    public SteveEntity getSteve(String name) {
        return activeSteves.get(name);
    }

    public SteveEntity getSteve(UUID uuid) {
        return stevesByUUID.get(uuid);
    }

    public boolean removeSteve(String name) {
        // Preserve the single-argument contract: also discard the tracked bot.
        SteveEntity tracked = activeSteves.remove(name);
        if (tracked != null) {
            stevesByUUID.remove(tracked.getUUID());
            tracked.discard();
            return true;
        }
        return false;
    }

    /**
     * Removes every live SteveEntity with the given name from all server
     * levels and cleans up the registries. The world sweep is required
     * because a bot loaded from NBT may exist in a chunk without being
     * tracked in the maps (e.g. after the dedup auto-spawn regression).
     */
    public boolean removeSteve(String name, MinecraftServer server) {
        boolean removed = false;
        if (server != null) {
            // Collect first, discard after: iterating getAllEntities() while
            // removing entities from it would throw a concurrent-modification.
            List<SteveEntity> matches = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof SteveEntity steve && name.equals(steve.getSteveName())) {
                        matches.add(steve);
                    }
                }
            }
            for (SteveEntity steve : matches) {
                steve.discard();
                removed = true;
            }
        }
        SteveEntity tracked = activeSteves.remove(name);
        if (tracked != null) {
            stevesByUUID.remove(tracked.getUUID());
            removed = true;
        }
        return removed;
    }

    /**
     * Cleans up the registries when a tracked Steve is unloaded from the
     * world (chunk unload / dimension change). The bot will be re-adopted
     * via {@link #adopt(SteveEntity)} when its chunk loads again.
     */
    public void onSteveUnload(SteveEntity steve) {
        if (steve == null) {
            return;
        }
        String name = steve.getSteveName();
        SteveEntity tracked = activeSteves.get(name);
        if (tracked == steve) {
            activeSteves.remove(name);
            SteveMod.LOGGER.info("Steve '{}' unloaded from world, removed from registry", name);
        }
        stevesByUUID.remove(steve.getUUID());
    }

    public void clearAllSteves() {
        SteveMod.LOGGER.info("Clearing {} Steve entities", activeSteves.size());
        for (SteveEntity steve : activeSteves.values()) {
            steve.discard();
        }
        activeSteves.clear();
        stevesByUUID.clear();    }

    public Collection<SteveEntity> getAllSteves() {
        return Collections.unmodifiableCollection(activeSteves.values());
    }

    public List<String> getSteveNames() {
        return new ArrayList<>(activeSteves.keySet());
    }

    public int getActiveCount() {
        return activeSteves.size();
    }

    public void tick(ServerLevel level) {
        // Clean up dead or removed Steves
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
}

