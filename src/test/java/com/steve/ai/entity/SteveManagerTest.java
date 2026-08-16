package com.steve.ai.entity;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.DataVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the SteveManager lifecycle: dedup on adopt, NBT-loaded
 * preference, registry cleanup (leave/tick) and removeSteve world sweep.
 *
 * Minecraft's registries are bootstrapped like in SteveInventoryTest; entity
 * interactions are mocked (no running game server is needed).
 */
class SteveManagerTest {

    @BeforeAll
    static void bootstrap() {
        try {
            SharedConstants.setVersion(new WorldVersion() {
                @Override
                public String getName() { return "1.20.1"; }
                @Override
                public String getId() { return "1.20.1"; }
                @Override
                public DataVersion getDataVersion() { return new DataVersion(3465); }
                @Override
                public int getProtocolVersion() { return 765; }
                @Override
                public int getPackVersion(PackType type) { return 15; }
                @Override
                public Date getBuildTime() { return new Date(0); }
                @Override
                public boolean isStable() { return true; }
            });
        } catch (IllegalStateException e) {
            // Version already set by another test class in the same JVM.
        }
        try {
            Bootstrap.bootStrap();
        } catch (IllegalStateException e) {
            // Already bootstrapped by another test class in the same JVM.
        }
    }

    private static SteveEntity mockSteve(String name, UUID uuid) {
        SteveEntity steve = mock(SteveEntity.class);
        when(steve.getSteveName()).thenReturn(name);
        when(steve.getUUID()).thenReturn(uuid);
        when(steve.isAlive()).thenReturn(true);
        when(steve.isRemoved()).thenReturn(false);
        when(steve.isLoadedFromNbt()).thenReturn(false);
        doNothing().when(steve).discard();
        return steve;
    }

    // ==================== adopt / dedup ====================

    @Test
    void adoptRegistersNewSteve() {
        SteveManager manager = new SteveManager();
        UUID uuid = UUID.randomUUID();
        SteveEntity steve = mockSteve("Steve", uuid);

        SteveEntity adopted = manager.adopt(steve);

        assertSame(steve, adopted);
        assertSame(steve, manager.getSteve("Steve"));
        assertSame(steve, manager.getSteve(uuid));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void adoptIsIdempotentForSameInstance() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Steve", UUID.randomUUID());

        assertSame(steve, manager.adopt(steve));
        assertSame(steve, manager.adopt(steve));

        assertEquals(1, manager.getActiveCount());
        verify(steve, never()).discard();
    }

    @Test
    void adoptDiscardsDuplicateNewcomer() {
        SteveManager manager = new SteveManager();
        SteveEntity original = mockSteve("Steve", UUID.randomUUID());
        SteveEntity duplicate = mockSteve("Steve", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getSteve("Steve"));
        assertNull(manager.getSteve(duplicate.getUUID()));
        verify(duplicate).setSuppressInventoryDrop(true);
        verify(duplicate).discard();
        verify(original, never()).discard();
    }

    @Test
    void adoptPrefersNbtLoadedOverFreshSpawn() {
        SteveManager manager = new SteveManager();
        SteveEntity fresh = mockSteve("Steve", UUID.randomUUID());
        UUID originalUuid = UUID.randomUUID();
        SteveEntity original = mockSteve("Steve", originalUuid);
        when(original.isLoadedFromNbt()).thenReturn(true);

        // The fresh spawn registers first (the original lives in an unloaded chunk).
        assertSame(fresh, manager.adopt(fresh));
        // When the original's chunk loads, the NBT-loaded bot must win.
        assertSame(original, manager.adopt(original));

        assertSame(original, manager.getSteve("Steve"));
        assertSame(original, manager.getSteve(originalUuid));
        assertNull(manager.getSteve(fresh.getUUID()));
        verify(fresh).setSuppressInventoryDrop(true);
        verify(fresh).discard();
        verify(original, never()).discard();
    }

    @Test
    void adoptKeepsFirstNbtLoadedInstanceOnDoubleNbtDedup() {
        SteveManager manager = new SteveManager();
        UUID uuidA = UUID.randomUUID();
        SteveEntity a = mockSteve("Steve", uuidA);
        when(a.isLoadedFromNbt()).thenReturn(true);
        SteveEntity b = mockSteve("Steve", UUID.randomUUID());
        when(b.isLoadedFromNbt()).thenReturn(true);

        assertSame(a, manager.adopt(a));
        assertNull(manager.adopt(b));

        assertSame(a, manager.getSteve("Steve"));
        verify(b).setSuppressInventoryDrop(true);
        verify(b).discard();
        verify(a, never()).discard();
    }

    @Test
    void adoptReplacesStaleDeadEntry() {
        SteveManager manager = new SteveManager();
        SteveEntity stale = mockSteve("Steve", UUID.randomUUID());
        when(stale.isAlive()).thenReturn(false);
        SteveEntity fresh = mockSteve("Steve", UUID.randomUUID());

        assertSame(stale, manager.adopt(stale));
        assertSame(fresh, manager.adopt(fresh));

        assertSame(fresh, manager.getSteve("Steve"));
        assertNull(manager.getSteve(stale.getUUID()));
        verify(stale, never()).discard();
    }

    @Test
    void adoptRejectsNull() {
        SteveManager manager = new SteveManager();
        assertNull(manager.adopt(null));
        assertEquals(0, manager.getActiveCount());
    }

    // ==================== onSteveUnload ====================

    @Test
    void onSteveUnloadRemovesTrackedEntry() {
        SteveManager manager = new SteveManager();
        UUID uuid = UUID.randomUUID();
        SteveEntity steve = mockSteve("Steve", uuid);
        manager.adopt(steve);

        manager.onSteveUnload(steve);

        assertNull(manager.getSteve("Steve"));
        assertNull(manager.getSteve(uuid));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void onSteveUnloadIgnoresUntrackedEntity() {
        SteveManager manager = new SteveManager();
        SteveEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        // A different same-named instance leaves - the registry must stay.
        SteveEntity other = mockSteve("Steve", UUID.randomUUID());
        manager.onSteveUnload(other);

        assertSame(tracked, manager.getSteve("Steve"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void onSteveUnloadToleratesNull() {
        SteveManager manager = new SteveManager();
        assertDoesNotThrow(() -> manager.onSteveUnload(null));
    }

    // ==================== tick ====================

    @Test
    void tickCleansDeadSteves() {
        SteveManager manager = new SteveManager();
        SteveEntity alive = mockSteve("Steve", UUID.randomUUID());
        SteveEntity dead = mockSteve("Alex", UUID.randomUUID());
        when(dead.isAlive()).thenReturn(false);
        manager.adopt(alive);
        manager.adopt(dead);

        manager.tick();

        assertSame(alive, manager.getSteve("Steve"));
        assertNull(manager.getSteve("Alex"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void tickCleansRemovedSteves() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);
        when(steve.isRemoved()).thenReturn(true);

        manager.tick();

        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void tickKeepsLiveSteves() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        manager.tick();

        assertEquals(1, manager.getActiveCount());
        verify(steve, never()).discard();
    }

    // ==================== removeSteve(String, MinecraftServer) ====================

    @Test
    void removeSteveSweepsAllWorldMatchesAndRegistry() {
        SteveManager manager = new SteveManager();
        SteveEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        UUID uuid2 = UUID.randomUUID();
        SteveEntity worldBot = mockSteve("Steve", uuid2);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, worldBot));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        assertTrue(manager.removeSteve("Steve", server));

        verify(tracked).discard();
        verify(worldBot).discard();
        assertNull(manager.getSteve("Steve"));
        assertNull(manager.getSteve(uuid2));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void removeSteveSuppressesInventoryDropForDuplicatesOnly() {
        SteveManager manager = new SteveManager();
        SteveEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        SteveEntity duplicate = mockSteve("Steve", UUID.randomUUID());
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, duplicate));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        manager.removeSteve("Steve", server);

        // The tracked instance keeps its normal inventory drop; the duplicate's drop is suppressed.
        verify(tracked, never()).setSuppressInventoryDrop(true);
        verify(duplicate).setSuppressInventoryDrop(true);
        verify(duplicate).discard();
    }

    @Test
    void removeSteveSkipsDeadOrRemovedEntities() {
        SteveManager manager = new SteveManager();
        SteveEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        SteveEntity dead = mockSteve("Steve", UUID.randomUUID());
        when(dead.isAlive()).thenReturn(false);
        SteveEntity removed = mockSteve("Steve", UUID.randomUUID());
        when(removed.isRemoved()).thenReturn(true);

        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, dead, removed));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        manager.removeSteve("Steve", server);

        verify(tracked).discard();
        verify(dead, never()).discard();
        verify(removed, never()).discard();
    }

    @Test
    void removeSteveReturnsFalseForUnknownName() {
        SteveManager manager = new SteveManager();
        assertFalse(manager.removeSteve("Nobody", null));
        assertFalse(manager.removeSteve(null, null));
    }

    @Test
    void removeSteveHandlesNullServer() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        assertTrue(manager.removeSteve("Steve", null));
        assertEquals(0, manager.getActiveCount());
    }
}
