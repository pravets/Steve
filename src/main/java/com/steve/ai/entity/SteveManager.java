package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active Steve entities. The tracking map is in-memory only, while the
 * Steve entities themselves persist in world chunks via NBT (issue #9): on
 * world load they resurrect on their own. This manager therefore ADOPTS
 * world-loaded Steves into the map (once) and deduplicates same-name
 * duplicates left behind by older saves.
 */
public class SteveManager {

    private final Map<String, SteveEntity> activeSteves = new ConcurrentHashMap<>();
    private final Map<UUID, SteveEntity> stevesByUUID = new ConcurrentHashMap<>();
    private boolean adoptionDone = false;

    public SteveManager() {
    }

    public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
        SteveMod.LOGGER.info("Current active Steves: {}", activeSteves.size());

        if (activeSteves.containsKey(name)) {
            SteveMod.LOGGER.warn("Steve name '{}' already exists", name);
            return null;
        }

        // Issue #9: a world-loaded Steve with this name may exist even though
        // the in-memory map does not know it yet - never spawn a duplicate.
        SteveEntity existing = findInWorld(level, name);
        if (existing != null) {
            SteveMod.LOGGER.warn("Steve '{}' already exists in the world - adopting instead of spawning", name);
            track(existing);
            return existing;
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
            return null;
        }

        try {
            steve.setSteveName(name);
            steve.setPos(position.x, position.y, position.z);
            boolean added = level.addFreshEntity(steve);
            if (added) {
                track(steve);
                SteveMod.LOGGER.info("Successfully spawned Steve: {} with UUID {} at {}", name, steve.getUUID(), position);
                return steve;
            } else {
                SteveMod.LOGGER.error("Failed to add Steve entity to world (addFreshEntity returned false)");
            }
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Exception during spawn setup", e);
        }
        return null;
    }

    public SteveEntity getSteve(String name) {
        return activeSteves.get(name);
    }

    public SteveEntity getSteve(UUID uuid) {
        return stevesByUUID.get(uuid);
    }

    /** Removes EVERY Steve with this name from the world (sweep) and the map. */
    public boolean removeSteve(String name, MinecraftServer server) {
        boolean removed = false;
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                for (SteveEntity steve : findInWorldByName(level, name)) {
                    steve.discard();
                    removed = true;
                }
            }
        }
        SteveEntity tracked = activeSteves.remove(name);
        if (tracked != null) {
            stevesByUUID.remove(tracked.getUUID());
            removed = true;
        }
        return removed;
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

    /**
     * Called every server tick per level: adopts world-loaded Steves into the
     * map (once per server run) and deduplicates same-name leftovers from old
     * saves - the map is the single source of truth afterwards.
     */
    public void tick(ServerLevel level) {
        if (!adoptionDone && level.dimension() == ServerLevel.OVERWORLD) {
            adoptionDone = true;
            adoptFromWorld(level);
        }

        // Clean up dead or removed Steves
        Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SteveEntity> entry = iterator.next();
            SteveEntity steve = entry.getValue();
            if (!steve.isAlive() || steve.isRemoved()) {
                iterator.remove();
                stevesByUUID.remove(steve.getUUID());
                SteveMod.LOGGER.info("Removed dead Steve: {}", entry.getKey());
            }
        }
    }

    private void adoptFromWorld(ServerLevel level) {
        for (SteveEntity steve : worldSteves(level)) {
            String name = steve.getSteveName();
            if (name == null || name.isBlank()) {
                continue;
            }
            SteveEntity known = activeSteves.get(name);
            if (known != null && known != steve) {
                // Same-name duplicate from an old save: keep the first one
                SteveMod.LOGGER.warn("Deduplicating Steve '{}' - discarding extra entity {}", name, steve.getUUID());
                steve.discard();
                continue;
            }
            if (known == null) {
                track(steve);
                SteveMod.LOGGER.info("Adopted world-loaded Steve: {} (UUID {})", name, steve.getUUID());
            }
        }
    }

    private void track(SteveEntity steve) {
        activeSteves.put(steve.getSteveName(), steve);
        stevesByUUID.put(steve.getUUID(), steve);
    }

    private static List<SteveEntity> worldSteves(ServerLevel level) {
        return level.getEntitiesOfClass(SteveEntity.class, AABB.ofSize(
            level.getSharedSpawnPos().getCenter(), 6000, 6000, 6000), EntitySelector.NO_SPECTATORS);
    }

    private static SteveEntity findInWorld(ServerLevel level, String name) {
        for (SteveEntity steve : worldSteves(level)) {
            if (name.equalsIgnoreCase(steve.getSteveName())) {
                return steve;
            }
        }
        return null;
    }

    private static List<SteveEntity> findInWorldByName(ServerLevel level, String name) {
        List<SteveEntity> result = new ArrayList<>();
        for (SteveEntity steve : worldSteves(level)) {
            if (name.equalsIgnoreCase(steve.getSteveName())) {
                result.add(steve);
            }
        }
        return result;
    }
}
