package ru.pravets.vasyan.entity;

import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VasyanWorldDataTest extends AbstractMinecraftTest {

    @Test
    void newDataHasBotsNotSpawned() {
        VasyanWorldData data = new VasyanWorldData();
        assertFalse(data.hasDefaultBotsSpawned());
    }

    @Test
    void markFlagsAsSpawned() {
        VasyanWorldData data = new VasyanWorldData();
        data.markDefaultBotsSpawned();
        assertTrue(data.hasDefaultBotsSpawned());
    }

    @Test
    void saveLoadRoundTripPreservesFlag() {
        VasyanWorldData data = new VasyanWorldData();
        data.markDefaultBotsSpawned();

        CompoundTag tag = new CompoundTag();
        data.save(tag);

        VasyanWorldData loaded = VasyanWorldData.load(tag);
        assertTrue(loaded.hasDefaultBotsSpawned());
    }

    @Test
    void loadDefaultsToFalse() {
        VasyanWorldData loaded = VasyanWorldData.load(new CompoundTag());
        assertFalse(loaded.hasDefaultBotsSpawned());
    }
}
