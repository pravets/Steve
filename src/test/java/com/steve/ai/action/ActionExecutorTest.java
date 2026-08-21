package com.steve.ai.action;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.testutil.AbstractMinecraftTest;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for ActionExecutor
 */
class ActionExecutorTest extends AbstractMinecraftTest {

    @BeforeAll
    static void loadSteveConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        SteveConfig.SPEC.correct(config);
        SteveConfig.SPEC.acceptConfig(config);
    }

    @Test
    void planningWatchdogResetsStuckPlanning() {
        // Given a Steve whose level reports not client-side (so GUI messages are skipped)
        SteveEntity steve = mock(SteveEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);

        ActionExecutor executor = new ActionExecutor(steve);

        // Replace the planning future with one that never completes
        CompletableFuture<ResponseParser.ParsedResponse> never = new CompletableFuture<>();
        executor.setPlanningFutureForTest(never, "chop 5 wood");

        assertTrue(executor.isPlanning());

        // Simulate 80 seconds of ticks (20 ticks/sec). The watchdog checks once per second
        // and the configured planning timeout is 75 seconds, so this must trigger.
        for (int i = 0; i < 20 * 80; i++) {
            executor.tick();
        }

        assertFalse(executor.isPlanning(), "Planning flag should be reset after timeout");
        assertTrue(never.isCancelled(), "Stuck future should be cancelled by watchdog");
    }
}
