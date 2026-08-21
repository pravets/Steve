package ru.pravets.vasyan.action;

import ru.pravets.vasyan.config.VasyanConfig;
import ru.pravets.vasyan.entity.VasyanEntity;
import ru.pravets.vasyan.llm.ResponseParser;
import ru.pravets.vasyan.memory.VasyanMemory;
import ru.pravets.vasyan.testutil.AbstractMinecraftTest;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for ActionExecutor
 */
class ActionExecutorTest extends AbstractMinecraftTest {

    @BeforeAll
    static void loadSteveConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        VasyanConfig.SPEC.correct(config);
        VasyanConfig.SPEC.acceptConfig(config);
    }

    @Test
    void planningWatchdogResetsStuckPlanning() {
        // Given a Steve whose level reports not client-side (so GUI messages are skipped)
        VasyanEntity steve = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);
        when(steve.getMemory()).thenReturn(memory);

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
        VasyanEntity steve = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);
        when(steve.getMemory()).thenReturn(memory);

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
        VasyanEntity steve = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);
        when(steve.getMemory()).thenReturn(memory);

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

        // Wait for stopCurrentAction() to cancel the future before releasing the latch,
        // otherwise the async planning task may complete naturally and bypass the
        // cancellation path.
        worker.join();
        latch.countDown();

        // Then planning state is cleanly reset and the future is cancelled
        assertFalse(executor.isPlanning(), "Planning flag should be reset after concurrent stop");
        assertTrue(blockingFuture.isCancelled(), "Planning future should be cancelled by concurrent stop");
    }

    /**
     * Future that blocks tick() inside {@link Future#get()} until the test releases it.
     * Used to simulate the stop-then-start race where a result from the first planning
     * request arrives after a second request already owns the planning state.
     */
    private static class BarrierFuture implements Future<ResponseParser.ParsedResponse> {
        private final ResponseParser.ParsedResponse response;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private volatile boolean cancelled = false;

        BarrierFuture(ResponseParser.ParsedResponse response, CountDownLatch entered, CountDownLatch release) {
            this.response = response;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ResponseParser.ParsedResponse get() throws InterruptedException, ExecutionException {
            entered.countDown();
            release.await();
            return response;
        }

        @Override
        public ResponseParser.ParsedResponse get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            entered.countDown();
            if (!release.await(timeout, unit)) {
                throw new TimeoutException();
            }
            return response;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    @Test
    void stalePlanningResultIsDiscardedAfterStopThenStart() throws InterruptedException {
        // Given a Steve whose level reports not client-side
        VasyanEntity steve = mock(VasyanEntity.class);
        Level level = mock(Level.class);
        PathNavigation navigation = mock(PathNavigation.class);
        VasyanMemory memory = mock(VasyanMemory.class);

        when(level.isClientSide()).thenReturn(false);
        when(level.players()).thenReturn(Collections.emptyList());
        when(steve.level()).thenReturn(level);
        when(steve.getSteveName()).thenReturn("TestSteve");
        when(steve.getNavigation()).thenReturn(navigation);
        when(steve.getMemory()).thenReturn(memory);

        ActionExecutor executor = new ActionExecutor(steve);

        // Build two different planning responses
        ResponseParser.ParsedResponse response1 = new ResponseParser.ParsedResponse(
            "first", "gather wood", List.of(new Task("gather", Map.of("resource", "wood", "quantity", 5))));
        ResponseParser.ParsedResponse response2 = new ResponseParser.ParsedResponse(
            "second", "mine stone", List.of(new Task("mine", Map.of("block", "stone", "quantity", 3))));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BarrierFuture future1 = new BarrierFuture(response1, entered, release);

        // Inject the first (slow) planning future
        executor.setPlanningFutureForTest(future1, "cmd1");
        assertTrue(executor.isPlanning(), "Steve should be planning before tick");

        // Start tick() on another thread; it will block inside future1.get()
        Thread tickThread = new Thread(executor::tick);
        tickThread.start();

        // Wait until tick() has entered Future.get()
        entered.await();

        // Simulate stop-then-start: cancel the first request and start a second one
        executor.stopCurrentAction();
        assertTrue(future1.isCancelled(), "BarrierFuture.cancel should record cancellation");
        CompletableFuture<ResponseParser.ParsedResponse> future2 = CompletableFuture.completedFuture(response2);
        executor.setPlanningFutureForTest(future2, "cmd2");

        // Release the barrier so the first tick() can return from get() and re-check request ID
        release.countDown();
        tickThread.join();

        // Then the stale first response must NOT have been applied
        assertNull(executor.getCurrentGoal(), "Stale plan should not be the current goal");
        assertNotEquals("gather wood", executor.getCurrentGoal(), "Stale plan must not be applied");
        assertTrue(executor.isPlanning(), "Second request should still be planning");

        // When tick() runs again it applies the second response
        executor.tick();

        assertEquals("mine stone", executor.getCurrentGoal(), "Second plan should be applied");
        assertTrue(executor.getQueuedTaskCount() > 0, "Second plan tasks should be queued");
    }
}
