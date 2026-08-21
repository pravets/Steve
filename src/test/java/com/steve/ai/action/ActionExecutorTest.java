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
import java.util.concurrent.CountDownLatch;

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

    @Test
    void stopCurrentActionCancelsPlanning() {
        // Given a Steve whose level reports not client-side
        SteveEntity steve = mock(SteveEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);

        ActionExecutor executor = new ActionExecutor(steve);

        // Start an async planning future that would never complete
        CompletableFuture<ResponseParser.ParsedResponse> never = new CompletableFuture<>();
        executor.setPlanningFutureForTest(never, "chop 5 wood");

        assertTrue(executor.isPlanning(), "Steve should be planning before stop");

        // When stopCurrentAction is called (triggered by /steve stop or stay)
        executor.stopCurrentAction();

        // Then planning is cancelled and state is reset
        assertFalse(executor.isPlanning(), "Planning flag should be reset after stop");
        assertTrue(never.isCancelled(), "Planning future should be cancelled by stop");
    }

    @Test
    void concurrentStopDuringPlanningDoesNotCorruptState() throws InterruptedException {
        // Given a Steve whose level reports not client-side
        SteveEntity steve = mock(SteveEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);

        ActionExecutor executor = new ActionExecutor(steve);

        // Start a planning future that blocks until we release the latch
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture<ResponseParser.ParsedResponse> blockingFuture = CompletableFuture.supplyAsync(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        executor.setPlanningFutureForTest(blockingFuture, "chop 5 wood");
        assertTrue(executor.isPlanning(), "Steve should be planning before stop");

        // When stopCurrentAction is called from a worker thread while planning is active
        Thread worker = new Thread(executor::stopCurrentAction);
        worker.start();

        // Let the threads race, then release the planning future and wait for the worker
        Thread.sleep(50);
        latch.countDown();
        worker.join();

        // Then planning state is cleanly reset and the future is cancelled
        assertFalse(executor.isPlanning(), "Planning flag should be reset after concurrent stop");
        assertTrue(blockingFuture.isCancelled(), "Planning future should be cancelled by concurrent stop");
    }
}
