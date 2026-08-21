package ru.pravets.vasyan.entity;

import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the VasyanManager lifecycle: dedup on adopt, NBT-loaded
 * preference, registry cleanup (leave/tick) and removeSteve world sweep.
 *
 * Minecraft's registries are bootstrapped in AbstractMinecraftTest; entity
 * interactions are mocked (no running game server is needed).
 */
class SteveManagerTest extends AbstractMinecraftTest {

    private static VasyanEntity mockSteve(String name, UUID uuid) {
        VasyanEntity steve = mock(VasyanEntity.class);
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
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity steve = mockSteve("Steve", uuid);

        VasyanEntity adopted = manager.adopt(steve);

        assertSame(steve, adopted);
        assertSame(steve, manager.getSteve("Steve"));
        assertSame(steve, manager.getSteve(uuid));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void adoptIsIdempotentForSameInstance() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity steve = mockSteve("Steve", UUID.randomUUID());

        assertSame(steve, manager.adopt(steve));
        assertSame(steve, manager.adopt(steve));

        assertEquals(1, manager.getActiveCount());
        verify(steve, never()).discard();
    }

    @Test
    void adoptRejectsDuplicateNewcomer() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity original = mockSteve("Steve", UUID.randomUUID());
        VasyanEntity duplicate = mockSteve("Steve", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getSteve("Steve"));
        assertNull(manager.getSteve(duplicate.getUUID()));
        // The rejected duplicate has not entered the world yet - the caller
        // (ServerEventHandler) cancels the join event instead of discarding.
        verify(duplicate, never()).setSuppressInventoryDrop(true);
        verify(duplicate, never()).discard();
        verify(original, never()).discard();
    }

    @Test
    void adoptPrefersNbtLoadedOverFreshSpawn() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity fresh = mockSteve("Steve", UUID.randomUUID());
        UUID originalUuid = UUID.randomUUID();
        VasyanEntity original = mockSteve("Steve", originalUuid);
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
        VasyanManager manager = new VasyanManager();
        UUID uuidA = UUID.randomUUID();
        VasyanEntity a = mockSteve("Steve", uuidA);
        when(a.isLoadedFromNbt()).thenReturn(true);
        VasyanEntity b = mockSteve("Steve", UUID.randomUUID());
        when(b.isLoadedFromNbt()).thenReturn(true);

        assertSame(a, manager.adopt(a));
        assertNull(manager.adopt(b));

        assertSame(a, manager.getSteve("Steve"));
        verify(b, never()).setSuppressInventoryDrop(true);
        verify(b, never()).discard();
        verify(a, never()).discard();
    }

    @Test
    void adoptReplacesStaleDeadEntry() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity stale = mockSteve("Steve", UUID.randomUUID());
        when(stale.isAlive()).thenReturn(false);
        VasyanEntity fresh = mockSteve("Steve", UUID.randomUUID());

        assertSame(stale, manager.adopt(stale));
        assertSame(fresh, manager.adopt(fresh));

        assertSame(fresh, manager.getSteve("Steve"));
        assertNull(manager.getSteve(stale.getUUID()));
        verify(stale, never()).discard();
    }

    @Test
    void adoptRejectsNull() {
        VasyanManager manager = new VasyanManager();
        assertNull(manager.adopt(null));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void adoptForcesCurrentChunkImmediately() {
        VasyanManager manager = new VasyanManager();
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        UUID uuid = UUID.randomUUID();
        VasyanEntity steve = mockSteve("Steve", uuid);
        when(steve.level()).thenReturn(level);
        when(steve.blockPosition()).thenReturn(new BlockPos(160, 64, 0));

        manager.adopt(steve);

        // 160,0 is chunk [10,0]
        verify(level).setChunkForced(10, 0, true);
        verify(steve).setForcedChunk(argThat(key ->
                key.dimension().equals(Level.OVERWORLD) && key.pos().equals(new ChunkPos(10, 0))));
    }

    @Test
    void adoptAfterChunkUnloadReusesExistingForce() {
        // Regression for issue #14: when a Steve unloads with its chunk
        // (UNLOADED_TO_CHUNK), the chunk stays force-loaded. Re-adopting the
        // same UUID must not increment the refcount, and removing the Steve
        // must release the chunk exactly once.
        VasyanManager manager = new VasyanManager();
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getServer()).thenReturn(server);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);

        UUID uuid = UUID.randomUUID();
        BlockPos pos = new BlockPos(160, 64, 0);
        VasyanEntity steve = mockSteve("Steve", uuid);
        when(steve.level()).thenReturn(level);
        when(steve.blockPosition()).thenReturn(pos);

        // First adopt: forces the chunk.
        manager.adopt(steve);
        verify(level).setChunkForced(10, 0, true);

        // Simulate chunk unload: entity leaves but force is intentionally kept.
        manager.onSteveUnload(steve);
        assertNull(manager.getSteve("Steve"));
        verify(level, never()).setChunkForced(10, 0, false);

        // Re-adopt after chunk reloads (same UUID, fresh mock instance).
        VasyanEntity reloaded = mockSteve("Steve", uuid);
        when(reloaded.level()).thenReturn(level);
        when(reloaded.blockPosition()).thenReturn(pos);
        ChunkForceTracker.ChunkKey reloadedChunk = new ChunkForceTracker.ChunkKey(Level.OVERWORLD, new ChunkPos(10, 0));
        when(reloaded.getForcedChunk()).thenReturn(reloadedChunk);

        manager.adopt(reloaded);
        // No additional setChunkForced(true) call -> refcount did not leak.
        verify(level, times(1)).setChunkForced(10, 0, true);

        // Now remove the Steve for good: chunk should be unforced exactly once.
        when(reloaded.isRemoved()).thenReturn(true);
        manager.tick(level);
        verify(level).setChunkForced(10, 0, false);
    }

    // ==================== onSteveUnload ====================

    @Test
    void onSteveUnloadRemovesTrackedEntry() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity steve = mockSteve("Steve", uuid);
        manager.adopt(steve);

        manager.onSteveUnload(steve);

        assertNull(manager.getSteve("Steve"));
        assertNull(manager.getSteve(uuid));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void onSteveUnloadIgnoresUntrackedEntity() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        // A different same-named instance leaves - the registry must stay.
        VasyanEntity other = mockSteve("Steve", UUID.randomUUID());
        manager.onSteveUnload(other);

        assertSame(tracked, manager.getSteve("Steve"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void onSteveUnloadToleratesNull() {
        VasyanManager manager = new VasyanManager();
        assertDoesNotThrow(() -> manager.onSteveUnload(null));
    }

    // ==================== tick ====================

    @Test
    void tickCleansDeadSteves() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity alive = mockSteve("Steve", UUID.randomUUID());
        VasyanEntity dead = mockSteve("Alex", UUID.randomUUID());
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
        VasyanManager manager = new VasyanManager();
        VasyanEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);
        when(steve.isRemoved()).thenReturn(true);

        manager.tick();

        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void tickKeepsLiveSteves() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        manager.tick();

        assertEquals(1, manager.getActiveCount());
        verify(steve, never()).discard();
    }

    // ==================== removeSteve(String, MinecraftServer) ====================

    @Test
    void removeSteveSweepsAllWorldMatchesAndRegistry() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        UUID uuid2 = UUID.randomUUID();
        VasyanEntity worldBot = mockSteve("Steve", uuid2);
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
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        VasyanEntity duplicate = mockSteve("Steve", UUID.randomUUID());
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
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        VasyanEntity dead = mockSteve("Steve", UUID.randomUUID());
        when(dead.isAlive()).thenReturn(false);
        VasyanEntity removed = mockSteve("Steve", UUID.randomUUID());
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
        VasyanManager manager = new VasyanManager();
        assertFalse(manager.removeSteve("Nobody", null));
        assertFalse(manager.removeSteve(null, null));
    }

    @Test
    void removeSteveHandlesNullServer() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        assertTrue(manager.removeSteve("Steve", null));
        assertEquals(0, manager.getActiveCount());
    }

    // ==================== case-insensitive name handling ====================

    @Test
    void getSteveMatchesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        assertSame(steve, manager.getSteve("steve"));
        assertSame(steve, manager.getSteve("STEVE"));
        assertSame(steve, manager.getSteve("StEvE"));
    }

    @Test
    void adoptRejectsDuplicateDifferingOnlyInCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity original = mockSteve("Steve", UUID.randomUUID());
        VasyanEntity duplicate = mockSteve("steve", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getSteve("Steve"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void removeSteveMatchesTrackedAndWorldEntitiesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(tracked);

        UUID worldUuid = UUID.randomUUID();
        VasyanEntity worldBot = mockSteve("STEVE", worldUuid);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, worldBot));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        assertTrue(manager.removeSteve("steve", server));

        verify(tracked).discard();
        verify(worldBot).discard();
        assertNull(manager.getSteve("Steve"));
        assertNull(manager.getSteve(worldUuid));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void onSteveUnloadRemovesEntryWhenNameCasingDiffersFromKey() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity steve = mockSteve("Steve", uuid);
        manager.adopt(steve);

        when(steve.getSteveName()).thenReturn("steve");
        manager.onSteveUnload(steve);

        assertNull(manager.getSteve("Steve"));
        assertNull(manager.getSteve(uuid));
        assertEquals(0, manager.getActiveCount());
    }
}
