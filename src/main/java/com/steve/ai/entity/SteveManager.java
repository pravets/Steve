package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import net.minecraft.world.World;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SteveManager {
    private final Map<String, SteveEntity> activeSteves = new ConcurrentHashMap<String, SteveEntity>();
    private final Map<UUID, SteveEntity> stevesByUUID = new ConcurrentHashMap<UUID, SteveEntity>();

    public SteveManager() {}

    public SteveEntity spawnSteve(World world, double x, double y, double z, String name) {
        if (activeSteves.containsKey(name)) return null;
        int maxSteves = SteveConfig.MAX_ACTIVE_STEVES;
        if (activeSteves.size() >= maxSteves) return null;

        try {
            SteveEntity steve = new SteveEntity(world);
            steve.setSteveName(name);
            steve.setPositionAndUpdate(x, y, z);
            world.spawnEntityInWorld(steve);
            activeSteves.put(name, steve);
            stevesByUUID.put(steve.getUniqueID(), steve);
            return steve;
        } catch (Throwable e) {
            SteveMod.LOGGER.error("Failed to spawn Steve", e);
            return null;
        }
    }

    public SteveEntity getSteve(String name) { return activeSteves.get(name); }
    public SteveEntity getSteve(UUID uuid) { return stevesByUUID.get(uuid); }

    public boolean removeSteve(String name) {
        SteveEntity steve = activeSteves.remove(name);
        if (steve != null) {
            stevesByUUID.remove(steve.getUniqueID());
            steve.setDead();
            return true;
        }
        return false;
    }

    public void clearAllSteves() {
        for (SteveEntity steve : activeSteves.values()) {
            steve.setDead();
        }
        activeSteves.clear();
        stevesByUUID.clear();
    }

    public Collection<SteveEntity> getAllSteves() {
        return Collections.unmodifiableCollection(activeSteves.values());
    }

    public List<String> getSteveNames() {
        return new ArrayList<String>(activeSteves.keySet());
    }

    public int getActiveCount() { return activeSteves.size(); }

    public void tick(World world) {
        Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SteveEntity> entry = iterator.next();
            SteveEntity steve = entry.getValue();
            if (steve.isDead) {
                iterator.remove();
                stevesByUUID.remove(steve.getUniqueID());
            }
        }
    }
}
