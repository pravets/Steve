package com.steve.ai.entity;

import com.steve.ai.SteveMod;
import com.steve.ai.testutil.AbstractMinecraftTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SteveEntityChunkForceTest extends AbstractMinecraftTest {

    @BeforeAll
    static void installManager() throws Exception {
        // SteveEntity.remove delegates chunk release to SteveMod's global manager.
        // Tests run without the mod constructor, so inject a real manager.
        Field field = SteveMod.class.getDeclaredField("steveManager");
        field.setAccessible(true);
        field.set(null, new SteveManager());
    }

    private static SteveEntity testSteve(ServerLevel level) {
        SteveEntity steve = mock(SteveEntity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(steve.level()).thenReturn(level);
        return steve;
    }

    @Test
    void unloadDoesNotReleaseForcedChunk() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isClientSide()).thenReturn(false);
        SteveEntity steve = testSteve(level);
        steve.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), new ChunkPos(0, 0)));

        // Simulate the chunk-release part of a chunk-unload removal
        steve.releaseForcedChunk(Entity.RemovalReason.UNLOADED_TO_CHUNK);

        assertNotNull(steve.getForcedChunk(), "forced chunk must survive UNLOADED_TO_CHUNK");
    }

    @Test
    void killedReleasesForcedChunk() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isClientSide()).thenReturn(false);
        SteveEntity steve = testSteve(level);
        steve.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), new ChunkPos(0, 0)));

        steve.releaseForcedChunk(Entity.RemovalReason.KILLED);

        assertNull(steve.getForcedChunk(), "forced chunk must be released on KILLED");
    }

    @Test
    void discardedReleasesForcedChunk() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isClientSide()).thenReturn(false);
        SteveEntity steve = testSteve(level);
        steve.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), new ChunkPos(0, 0)));

        steve.releaseForcedChunk(Entity.RemovalReason.DISCARDED);

        assertNull(steve.getForcedChunk(), "forced chunk must be released on DISCARDED");
    }

    @Test
    void dimensionChangeDoesNotReleaseForcedChunk() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.isClientSide()).thenReturn(false);
        SteveEntity steve = testSteve(level);
        steve.setForcedChunk(new ChunkForceTracker.ChunkKey(level.dimension(), new ChunkPos(0, 0)));

        steve.releaseForcedChunk(Entity.RemovalReason.CHANGED_DIMENSION);

        assertNotNull(steve.getForcedChunk(), "forced chunk must survive CHANGED_DIMENSION");
    }
}
