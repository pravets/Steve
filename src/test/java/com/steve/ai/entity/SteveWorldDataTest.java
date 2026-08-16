package com.steve.ai.entity;

import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteveWorldDataTest extends AbstractMinecraftTest {

    @Test
    void newDataHasBotsNotSpawned() {
        SteveWorldData data = new SteveWorldData();
        assertFalse(data.hasDefaultBotsSpawned());
    }

    @Test
    void markFlagsAsSpawned() {
        SteveWorldData data = new SteveWorldData();
        data.markDefaultBotsSpawned();
        assertTrue(data.hasDefaultBotsSpawned());
    }

    @Test
    void saveLoadRoundTripPreservesFlag() {
        SteveWorldData data = new SteveWorldData();
        data.markDefaultBotsSpawned();

        CompoundTag tag = new CompoundTag();
        data.save(tag);

        SteveWorldData loaded = SteveWorldData.load(tag);
        assertTrue(loaded.hasDefaultBotsSpawned());
    }

    @Test
    void loadDefaultsToFalse() {
        SteveWorldData loaded = SteveWorldData.load(new CompoundTag());
        assertFalse(loaded.hasDefaultBotsSpawned());
    }
}
