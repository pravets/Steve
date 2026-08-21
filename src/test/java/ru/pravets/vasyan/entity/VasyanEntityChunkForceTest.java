package ru.pravets.vasyan.entity;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VasyanEntityChunkForceTest extends AbstractMinecraftTest {

    private static VasyanManager manager;

    @BeforeAll
    static void installManager() throws Exception {
        manager = new VasyanManager();
        Field field = VasyanMod.class.getDeclaredField("vasyanManager");
        field.setAccessible(true);
        field.set(null, manager);
    }

    @BeforeEach
    void resetManager() throws Exception {
        // Provide each test with a fresh tracker to avoid cross-test refcount state.
        manager = new VasyanManager();
        Field field = VasyanMod.class.getDeclaredField("vasyanManager");
        field.setAccessible(true);
        field.set(null, manager);
    }

    private static ServerLevel mockLevel(ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.dimension()).thenReturn(dimension);
        when(level.isClientSide()).thenReturn(false);
        when(level.getServer()).thenReturn(server);
        when(server.getLevel(dimension)).thenReturn(level);
        return level;
    }

    private static VasyanEntity testVasyan(String name, UUID uuid, ServerLevel level, ChunkPos chunkPos) {
        VasyanEntity vasyan = mock(VasyanEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(vasyan.getVasyanName()).thenReturn(name);
        when(vasyan.getUUID()).thenReturn(uuid);
        when(vasyan.level()).thenReturn(level);
        when(vasyan.blockPosition()).thenReturn(new BlockPos(chunkPos.getMinBlockX(), 64, chunkPos.getMinBlockZ()));
        return vasyan;
    }

    @Test
    void unloadDoesNotReleaseForcedChunk() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(7, 9);
        ServerLevel level = mockLevel(Level.OVERWORLD);
        VasyanEntity vasyan = testVasyan("Vasyan", uuid, level, pos);

        manager.forceChunk(level, pos, uuid);
        vasyan.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), pos));

        vasyan.releaseForcedChunk(Entity.RemovalReason.UNLOADED_TO_CHUNK);

        assertNotNull(vasyan.getForcedChunk(), "forced chunk must survive UNLOADED_TO_CHUNK");
        assertEquals(1, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));
        verify(level, never()).setChunkForced(pos.x, pos.z, false);
    }

    @Test
    void killedReleasesForcedChunk() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(7, 10);
        ServerLevel level = mockLevel(Level.OVERWORLD);
        VasyanEntity vasyan = testVasyan("Vasyan", uuid, level, pos);

        manager.forceChunk(level, pos, uuid);
        vasyan.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), pos));

        vasyan.releaseForcedChunk(Entity.RemovalReason.KILLED);

        assertNull(vasyan.getForcedChunk(), "forced chunk must be released on KILLED");
        assertEquals(0, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));
        verify(level).setChunkForced(pos.x, pos.z, false);
    }

    @Test
    void discardedReleasesForcedChunk() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(7, 11);
        ServerLevel level = mockLevel(Level.OVERWORLD);
        VasyanEntity vasyan = testVasyan("Vasyan", uuid, level, pos);

        manager.forceChunk(level, pos, uuid);
        vasyan.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), pos));

        vasyan.releaseForcedChunk(Entity.RemovalReason.DISCARDED);

        assertNull(vasyan.getForcedChunk(), "forced chunk must be released on DISCARDED");
        assertEquals(0, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));
        verify(level).setChunkForced(pos.x, pos.z, false);
    }

    @Test
    void dimensionChangeDoesNotReleaseForcedChunk() {
        UUID uuid = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(7, 12);
        ServerLevel level = mockLevel(Level.OVERWORLD);
        VasyanEntity vasyan = testVasyan("Vasyan", uuid, level, pos);

        manager.forceChunk(level, pos, uuid);
        vasyan.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), pos));

        vasyan.releaseForcedChunk(Entity.RemovalReason.CHANGED_DIMENSION);

        assertNotNull(vasyan.getForcedChunk(), "forced chunk must survive CHANGED_DIMENSION");
        assertEquals(1, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));
        verify(level, never()).setChunkForced(pos.x, pos.z, false);
    }

    @Test
    void sharedChunkSurvivesSingleRelease() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ChunkPos pos = new ChunkPos(8, 8);
        ServerLevel level = mockLevel(Level.OVERWORLD);

        manager.forceChunk(level, pos, a);
        manager.forceChunk(level, pos, b);
        assertEquals(2, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));

        VasyanEntity vasyan = testVasyan("Vasyan", a, level, pos);
        vasyan.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), pos));
        vasyan.releaseForcedChunk(Entity.RemovalReason.KILLED);

        assertNull(vasyan.getForcedChunk());
        assertEquals(1, manager.getChunkForceTracker().holders(Level.OVERWORLD, pos));
        // The other holder keeps the chunk physically force-loaded.
        verify(level, times(1)).setChunkForced(pos.x, pos.z, true);
        verify(level, never()).setChunkForced(pos.x, pos.z, false);
    }
}
