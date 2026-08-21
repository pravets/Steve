package ru.pravets.vasyan.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persisted marker that controls the one-time spawn of the default
 * bots. Stored in the overworld's {@link net.minecraft.world.level.storage.DimensionDataStorage},
 * so it survives chunk unloads and player logouts - unlike a static JVM flag,
 * which was reset on every player logout and caused default bots to be
 * re-spawned (and duplicated) on each login.
 */
public class VasyanWorldData extends SavedData {
    public static final String DATA_NAME = "vasyan_default_bots_spawned";
    private static final String TAG_DEFAULT_BOTS_SPAWNED = "DefaultBotsSpawned";

    private boolean defaultBotsSpawned;

    public static VasyanWorldData load(CompoundTag tag) {
        VasyanWorldData data = new VasyanWorldData();
        data.defaultBotsSpawned = tag.getBoolean(TAG_DEFAULT_BOTS_SPAWNED);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean(TAG_DEFAULT_BOTS_SPAWNED, this.defaultBotsSpawned);
        return tag;
    }

    public boolean hasDefaultBotsSpawned() {
        return this.defaultBotsSpawned;
    }

    public void markDefaultBotsSpawned() {
        this.defaultBotsSpawned = true;
        this.setDirty();
    }
}
