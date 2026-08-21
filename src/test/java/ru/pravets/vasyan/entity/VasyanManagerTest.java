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
 * preference, registry cleanup (leave/tick) and removeVasyan world sweep.
 *
 * Minecraft's registries are bootstrapped in AbstractMinecraftTest; entity
 * interactions are mocked (no running game server is needed).
 */
class VasyanManagerTest extends AbstractMinecraftTest {

    private static VasyanEntity mockVasyan(String name, UUID uuid) {
        VasyanEntity vasyan = mock(VasyanEntity.class);
        when(vasyan.getVasyanName()).thenReturn(name);
        when(vasyan.getUUID()).thenReturn(uuid);
        when(vasyan.isAlive()).thenReturn(true);
        when(vasyan.isRemoved()).thenReturn(false);
        when(vasyan.isLoadedFromNbt()).thenReturn(false);
        doNothing().when(vasyan).discard();
        return vasyan;
    }

    // ==================== adopt / dedup ====================

    @Test
    void adoptRegistersNewVasyan() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity vasyan = mockVasyan("Vasyan", uuid);

        VasyanEntity adopted = manager.adopt(vasyan);

        assertSame(vasyan, adopted);
        assertSame(vasyan, manager.getVasyan("Vasyan"));
        assertSame(vasyan, manager.getVasyan(uuid));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void adoptIsIdempotentForSameInstance() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());

        assertSame(vasyan, manager.adopt(vasyan));
        assertSame(vasyan, manager.adopt(vasyan));

        assertEquals(1, manager.getActiveCount());
        verify(vasyan, never()).discard();
    }

    @Test
    void adoptRejectsDuplicateNewcomer() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity original = mockVasyan("Vasyan", UUID.randomUUID());
        VasyanEntity duplicate = mockVasyan("Vasyan", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(duplicate.getUUID()));
        // The rejected duplicate has not entered the world yet - the caller
        // (ServerEventHandler) cancels the join event instead of discarding.
        verify(duplicate, never()).setSuppressInventoryDrop(true);
        verify(duplicate, never()).discard();
        verify(original, never()).discard();
    }

    @Test
    void adoptPrefersNbtLoadedOverFreshSpawn() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity fresh = mockVasyan("Vasyan", UUID.randomUUID());
        UUID originalUuid = UUID.randomUUID();
        VasyanEntity original = mockVasyan("Vasyan", originalUuid);
        when(original.isLoadedFromNbt()).thenReturn(true);

        // The fresh spawn registers first (the original lives in an unloaded chunk).
        assertSame(fresh, manager.adopt(fresh));
        // When the original's chunk loads, the NBT-loaded bot must win.
        assertSame(original, manager.adopt(original));

        assertSame(original, manager.getVasyan("Vasyan"));
        assertSame(original, manager.getVasyan(originalUuid));
        assertNull(manager.getVasyan(fresh.getUUID()));
        verify(fresh).setSuppressInventoryDrop(true);
        verify(fresh).discard();
        verify(original, never()).discard();
    }

    @Test
    void adoptKeepsFirstNbtLoadedInstanceOnDoubleNbtDedup() {
        VasyanManager manager = new VasyanManager();
        UUID uuidA = UUID.randomUUID();
        VasyanEntity a = mockVasyan("Vasyan", uuidA);
        when(a.isLoadedFromNbt()).thenReturn(true);
        VasyanEntity b = mockVasyan("Vasyan", UUID.randomUUID());
        when(b.isLoadedFromNbt()).thenReturn(true);

        assertSame(a, manager.adopt(a));
        assertNull(manager.adopt(b));

        assertSame(a, manager.getVasyan("Vasyan"));
        verify(b, never()).setSuppressInventoryDrop(true);
        verify(b, never()).discard();
        verify(a, never()).discard();
    }

    @Test
    void adoptReplacesStaleDeadEntry() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity stale = mockVasyan("Vasyan", UUID.randomUUID());
        when(stale.isAlive()).thenReturn(false);
        VasyanEntity fresh = mockVasyan("Vasyan", UUID.randomUUID());

        assertSame(stale, manager.adopt(stale));
        assertSame(fresh, manager.adopt(fresh));

        assertSame(fresh, manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(stale.getUUID()));
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
        VasyanEntity vasyan = mockVasyan("Vasyan", uuid);
        when(vasyan.level()).thenReturn(level);
        when(vasyan.blockPosition()).thenReturn(new BlockPos(160, 64, 0));

        manager.adopt(vasyan);

        // 160,0 is chunk [10,0]
        verify(level).setChunkForced(10, 0, true);
        verify(vasyan).setForcedChunk(argThat(key ->
                key.dimension().equals(Level.OVERWORLD) && key.pos().equals(new ChunkPos(10, 0))));
    }

    @Test
    void adoptAfterChunkUnloadReusesExistingForce() {
        // Regression for issue #14: when a Vasyan unloads with its chunk
        // (UNLOADED_TO_CHUNK), the chunk stays force-loaded. Re-adopting the
        // same UUID must not increment the refcount, and removing the Vasyan
        // must release the chunk exactly once.
        VasyanManager manager = new VasyanManager();
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getServer()).thenReturn(server);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);

        UUID uuid = UUID.randomUUID();
        BlockPos pos = new BlockPos(160, 64, 0);
        VasyanEntity vasyan = mockVasyan("Vasyan", uuid);
        when(vasyan.level()).thenReturn(level);
        when(vasyan.blockPosition()).thenReturn(pos);

        // First adopt: forces the chunk.
        manager.adopt(vasyan);
        verify(level).setChunkForced(10, 0, true);

        // Simulate chunk unload: entity leaves but force is intentionally kept.
        manager.onVasyanUnload(vasyan);
        assertNull(manager.getVasyan("Vasyan"));
        verify(level, never()).setChunkForced(10, 0, false);

        // Re-adopt after chunk reloads (same UUID, fresh mock instance).
        VasyanEntity reloaded = mockVasyan("Vasyan", uuid);
        when(reloaded.level()).thenReturn(level);
        when(reloaded.blockPosition()).thenReturn(pos);
        ChunkForceTracker.ChunkKey reloadedChunk = new ChunkForceTracker.ChunkKey(Level.OVERWORLD, new ChunkPos(10, 0));
        when(reloaded.getForcedChunk()).thenReturn(reloadedChunk);

        manager.adopt(reloaded);
        // No additional setChunkForced(true) call -> refcount did not leak.
        verify(level, times(1)).setChunkForced(10, 0, true);

        // Now remove the Vasyan for good: chunk should be unforced exactly once.
        when(reloaded.isRemoved()).thenReturn(true);
        manager.tick(level);
        verify(level).setChunkForced(10, 0, false);
    }

    // ==================== onVasyanUnload ====================

    @Test
    void onVasyanUnloadRemovesTrackedEntry() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity vasyan = mockVasyan("Vasyan", uuid);
        manager.adopt(vasyan);

        manager.onVasyanUnload(vasyan);

        assertNull(manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(uuid));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void onVasyanUnloadIgnoresUntrackedEntity() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(tracked);

        // A different same-named instance leaves - the registry must stay.
        VasyanEntity other = mockVasyan("Vasyan", UUID.randomUUID());
        manager.onVasyanUnload(other);

        assertSame(tracked, manager.getVasyan("Vasyan"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void onVasyanUnloadToleratesNull() {
        VasyanManager manager = new VasyanManager();
        assertDoesNotThrow(() -> manager.onVasyanUnload(null));
    }

    // ==================== tick ====================

    @Test
    void tickCleansDeadVasyans() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity alive = mockVasyan("Vasyan", UUID.randomUUID());
        VasyanEntity dead = mockVasyan("Alex", UUID.randomUUID());
        when(dead.isAlive()).thenReturn(false);
        manager.adopt(alive);
        manager.adopt(dead);

        manager.tick();

        assertSame(alive, manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan("Alex"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void tickCleansRemovedVasyans() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(vasyan);
        when(vasyan.isRemoved()).thenReturn(true);

        manager.tick();

        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void tickKeepsLiveVasyans() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(vasyan);

        manager.tick();

        assertEquals(1, manager.getActiveCount());
        verify(vasyan, never()).discard();
    }

    // ==================== removeVasyan(String, MinecraftServer) ====================

    @Test
    void removeVasyanSweepsAllWorldMatchesAndRegistry() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(tracked);

        UUID uuid2 = UUID.randomUUID();
        VasyanEntity worldBot = mockVasyan("Vasyan", uuid2);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, worldBot));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        assertTrue(manager.removeVasyan("Vasyan", server));

        verify(tracked).discard();
        verify(worldBot).discard();
        assertNull(manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(uuid2));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void removeVasyanSuppressesInventoryDropForDuplicatesOnly() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(tracked);

        VasyanEntity duplicate = mockVasyan("Vasyan", UUID.randomUUID());
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, duplicate));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        manager.removeVasyan("Vasyan", server);

        // The tracked instance keeps its normal inventory drop; the duplicate's drop is suppressed.
        verify(tracked, never()).setSuppressInventoryDrop(true);
        verify(duplicate).setSuppressInventoryDrop(true);
        verify(duplicate).discard();
    }

    @Test
    void removeVasyanSkipsDeadOrRemovedEntities() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(tracked);

        VasyanEntity dead = mockVasyan("Vasyan", UUID.randomUUID());
        when(dead.isAlive()).thenReturn(false);
        VasyanEntity removed = mockVasyan("Vasyan", UUID.randomUUID());
        when(removed.isRemoved()).thenReturn(true);

        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, dead, removed));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        manager.removeVasyan("Vasyan", server);

        verify(tracked).discard();
        verify(dead, never()).discard();
        verify(removed, never()).discard();
    }

    @Test
    void removeVasyanReturnsFalseForUnknownName() {
        VasyanManager manager = new VasyanManager();
        assertFalse(manager.removeVasyan("Nobody", null));
        assertFalse(manager.removeVasyan(null, null));
    }

    @Test
    void removeVasyanHandlesNullServer() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(vasyan);

        assertTrue(manager.removeVasyan("Vasyan", null));
        assertEquals(0, manager.getActiveCount());
    }

    // ==================== case-insensitive name handling ====================

    @Test
    void getVasyanMatchesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(vasyan);

        assertSame(vasyan, manager.getVasyan("vasyan"));
        assertSame(vasyan, manager.getVasyan("VASYAN"));
        assertSame(vasyan, manager.getVasyan("VaSyAn"));
    }

    @Test
    void adoptRejectsDuplicateDifferingOnlyInCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity original = mockVasyan("Vasyan", UUID.randomUUID());
        VasyanEntity duplicate = mockVasyan("vasyan", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getVasyan("Vasyan"));
        assertEquals(1, manager.getActiveCount());
    }

    @Test
    void removeVasyanMatchesTrackedAndWorldEntitiesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity tracked = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(tracked);

        UUID worldUuid = UUID.randomUUID();
        VasyanEntity worldBot = mockVasyan("VASYAN", worldUuid);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getAllEntities()).thenReturn(List.of(tracked, worldBot));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(level));

        assertTrue(manager.removeVasyan("vasyan", server));

        verify(tracked).discard();
        verify(worldBot).discard();
        assertNull(manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(worldUuid));
        assertEquals(0, manager.getActiveCount());
    }

    @Test
    void onVasyanUnloadRemovesEntryWhenNameCasingDiffersFromKey() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity vasyan = mockVasyan("Vasyan", uuid);
        manager.adopt(vasyan);

        when(vasyan.getVasyanName()).thenReturn("vasyan");
        manager.onVasyanUnload(vasyan);

        assertNull(manager.getVasyan("Vasyan"));
        assertNull(manager.getVasyan(uuid));
        assertEquals(0, manager.getActiveCount());
    }
}
