package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.debug.AgentDebugBuffer;
import com.steve.ai.di.ServiceContainer;
import com.steve.ai.di.SimpleServiceContainer;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.execution.*;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.plugin.PluginManager;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Executes actions for a Steve entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final Queue<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle
    /** When true the Steve stays in place (stay/stop command) until the next command. */
    private volatile boolean staying = false;

    // NEW: Async planning support (non-blocking LLM calls)
    private final Object planningLock = new Object();
    private Future<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;
    private String pendingCommand;  // Store command while planning
    private int planningStartTick = -1;
    private static final int PLANNING_CHECK_INTERVAL = 20; // once per second

    // NEW: Monotonic request ID so each planning attempt owns its lifecycle atomically
    private long planningRequestSequence = 0L;
    private long activePlanningRequestId = 0L;

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;
        this.pendingCommand = null;

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, steve.getSteveName());
        this.interceptorChain = new InterceptorChain();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, steve.getSteveName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        SteveMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Steve '{}'",
            steve.getSteveName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    public void processNaturalLanguageCommand(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (async): {}", steve.getSteveName(), command);

        long requestId;
        synchronized (planningLock) {
            // If already planning, ignore new commands
            if (isPlanning) {
                SteveMod.LOGGER.warn("Steve '{}' is already planning, ignoring command: {}", steve.getSteveName(), command);
                sendToGUI(steve.getSteveName(), "Hold on, I'm still thinking about the previous command...");
                return;
            }

            // Reserve the planning request atomically with the state check.
            this.pendingCommand = command;
            this.isPlanning = true;
            this.planningStartTick = this.ticksSinceLastAction;
            requestId = ++planningRequestSequence;
            this.activePlanningRequestId = requestId;
        }

        // A new command wakes the Steve up from "stay in place".
        // Mutate on the server thread: tick() reads this flag on the game
        // thread and processNaturalLanguageCommand runs on a worker thread.
        var server = steve.level().getServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> staying = false);
        } else {
            staying = false;
        }

        // Cancel any current actions
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        // Send immediate feedback to user
        sendToGUI(steve.getSteveName(), "Thinking...");

        // Start async LLM call outside the lock.  The future is published only
        // if the reserved request is still active; a stop/stay that ran in the
        // meantime invalidated it and we must abort the now-stale request.
        CompletableFuture<ResponseParser.ParsedResponse> future;
        try {
            future = getTaskPlanner().planTasksAsync(steve, command);
        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
            synchronized (planningLock) {
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
            return;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error starting async planning", e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong!");
            synchronized (planningLock) {
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
            return;
        }

        synchronized (planningLock) {
            if (activePlanningRequestId == requestId) {
                this.planningFuture = future;
                SteveMod.LOGGER.info("Steve '{}' started async planning for: {}", steve.getSteveName(), command);
            } else {
                // Request was cancelled between reservation and publication.
                future.cancel(true);
                SteveMod.LOGGER.debug("Steve '{}' discarded planning future for cancelled request {}",
                    steve.getSteveName(), requestId);
            }
        }
    }

    /**
     * Legacy synchronous command processing (blocking).
     *
     * <p><b>Warning:</b> This method blocks the game thread for 30-60 seconds during LLM calls.
     * Use {@link #processNaturalLanguageCommand(String)} instead for non-blocking execution.</p>
     *
     * @param command The natural language command
     * @deprecated Use {@link #processNaturalLanguageCommand(String)} instead
     */
    @Deprecated
    public void processNaturalLanguageCommandSync(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (SYNC - blocking!): {}", steve.getSteveName(), command);

        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            // BLOCKING CALL - freezes game for 30-60 seconds!
            ResponseParser.ParsedResponse response = getTaskPlanner().planTasks(steve, command);

            if (response == null) {
                sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                return;
            }

            currentGoal = response.getPlan();
            steve.getMemory().setCurrentGoal(currentGoal);

            taskQueue.clear();
            taskQueue.addAll(response.getTasks());

            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
            }
        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
        }

        SteveMod.LOGGER.info("Steve '{}' queued {} tasks", steve.getSteveName(), taskQueue.size());
    }
    
    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
     */
    private void sendToGUI(String steveName, String message) {
        if (steve.level().isClientSide) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steveName, message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;

        // Check if async planning is complete (non-blocking check!)
        Future<ResponseParser.ParsedResponse> completedFuture = null;
        long expectedId = 0L;
        synchronized (planningLock) {
            if (isPlanning && planningFuture != null && planningFuture.isDone()) {
                completedFuture = planningFuture;
                expectedId = activePlanningRequestId;
            }
        }

        if (completedFuture != null) {
            ResponseParser.ParsedResponse response = null;
            Exception planningError = null;
            try {
                response = completedFuture.get();
            } catch (java.util.concurrent.CancellationException e) {
                planningError = e;
            } catch (java.util.concurrent.ExecutionException e) {
                planningError = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                planningError = e;
            } catch (Exception e) {
                planningError = e;
            }

            synchronized (planningLock) {
                if (expectedId != activePlanningRequestId) {
                    // A stop/start race happened while we were waiting for the future:
                    // this result belongs to an older request, so discard it silently.
                    SteveMod.LOGGER.debug("Steve '{}' discarding stale planning result for request {}",
                        steve.getSteveName(), expectedId);
                    return;
                }

                if (planningError instanceof java.util.concurrent.CancellationException) {
                    SteveMod.LOGGER.info("Steve '{}' planning was cancelled", steve.getSteveName());
                    sendToGUI(steve.getSteveName(), "Planning cancelled.");
                } else if (planningError != null) {
                    SteveMod.LOGGER.error("Steve '{}' failed to get planning result", steve.getSteveName(), planningError);
                    sendToGUI(steve.getSteveName(), "Oops, something went wrong while planning!");
                } else if (response != null) {
                    currentGoal = response.getPlan();
                    steve.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    taskQueue.addAll(response.getTasks());

                    AgentDebugBuffer.log(steve.getSteveName(), "PLAN",
                        "goal=\"" + truncate(currentGoal, 200) + "\", queued tasks: " + response.getTasks().size());

                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
                    }

                    SteveMod.LOGGER.info("Steve '{}' async planning complete: {} tasks queued",
                        steve.getSteveName(), taskQueue.size());
                } else {
                    sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                    AgentDebugBuffer.log(steve.getSteveName(), "PLAN", "planning returned null (see LLM/PARSE events)");
                    SteveMod.LOGGER.warn("Steve '{}' async planning returned null response", steve.getSteveName());
                }

                resetPlanningStateLocked();
            }
        }

        synchronized (planningLock) {
            if (isPlanning && planningStartTick >= 0 &&
                (ticksSinceLastAction - planningStartTick) % PLANNING_CHECK_INTERVAL == 0 &&
                (ticksSinceLastAction - planningStartTick) / PLANNING_CHECK_INTERVAL >= 1) {

                int elapsedSeconds = (ticksSinceLastAction - planningStartTick) / 20;
                if (elapsedSeconds >= SteveConfig.PLANNING_TIMEOUT_SECONDS.get()) {
                    SteveMod.LOGGER.warn("Steve '{}' planning timed out after {}s", steve.getSteveName(), elapsedSeconds);
                    AgentDebugBuffer.log(steve.getSteveName(), "PLAN", "planning timed out after " + elapsedSeconds + "s");

                    if (planningFuture != null) {
                        planningFuture.cancel(true);
                    }

                    sendToGUI(steve.getSteveName(), "LLM planning timed out — please try again.");
                    resetPlanningStateLocked();
                    activePlanningRequestId = ++planningRequestSequence;
                }
            }
        }

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})", 
                    steve.getSteveName(), result.getMessage(), result.isSuccess());
                AgentDebugBuffer.log(steve.getSteveName(), result.isSuccess() ? "ACTION_DONE" : "ACTION_FAIL",
                    currentAction.getClass().getSimpleName() + " -> " + truncate(result.getMessage(), 200));
                
                steve.getMemory().addAction(currentAction.getDescription());
                
                if (!result.isSuccess() && result.requiresReplanning()) {
                    // Action failed, need to replan
                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Problem: " + result.getMessage());
                    }
                }
                
                currentAction = null;
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}", 
                        steve.getSteveName(), currentAction.getDescription());
                }
                try {
                    currentAction.tick();
                } catch (Exception e) {
                    // An action crash must never leave the Steve silently
                    // standing: report it as an honest failure.
                    SteveMod.LOGGER.error("Steve '{}' action '{}' crashed",
                        steve.getSteveName(), currentAction.getClass().getSimpleName(), e);
                    AgentDebugBuffer.log(steve.getSteveName(), "ACTION_FAIL",
                        currentAction.getClass().getSimpleName() + " crashed: " + e);
                    currentAction.cancel();
                    currentAction = null;
                }
                return;
            }
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                executeTask(nextTask);
                ticksSinceLastAction = 0;
                return;
            }
        }
        
        // When completely idle (no tasks, no goal), follow nearest player
        // (unless told to stay in place)
        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null && !staying) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                // Restart idle following if it stopped
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else {
                // Continue idle following
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    private void executeTask(Task task) {
        SteveMod.LOGGER.info("Steve '{}' executing task: {} (action type: {})", 
            steve.getSteveName(), task, task.getAction());
        
        currentAction = createAction(task);
        
        if (currentAction == null) {
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            AgentDebugBuffer.log(steve.getSteveName(), "NO_ACTION",
                "no factory for action '" + task.getAction() + "', params=" + task.getParameters());
            return;
        }

        SteveMod.LOGGER.info("Created action: {} - starting now...", currentAction.getClass().getSimpleName());
        currentAction.start();
        AgentDebugBuffer.log(steve.getSteveName(), "ACTION_START",
            currentAction.getClass().getSimpleName() + " " + task.getAction() + " " + task.getParameters());
        SteveMod.LOGGER.info("Action started! Is complete: {}", currentAction.isComplete());
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, steve, task, actionContext);
            if (action != null) {
                SteveMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        SteveMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "teleport" -> new TeleportAction(steve, task);
            case "stay" -> new StayAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            case "build" -> new BuildStructureAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    /**
     * Removes all pending tasks without cancelling the currently running
     * action. Used by "stay": after the current task, the Steve must not
     * continue executing a multi-task plan.
     */
    public void clearTaskQueue() {
        taskQueue.clear();
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;
        // Reset state machine
        stateMachine.reset();
        cancelPlanning();
    }

    /**
     * Cancels an in-progress async LLM planning future and clears all
     * planning-related state. Called from {@link #stopCurrentAction()} so
     * stop/stay commands abort planning as well as execution.
     */
    private void cancelPlanning() {
        synchronized (planningLock) {
            if (isPlanning || planningFuture != null) {
                if (planningFuture != null) {
                    planningFuture.cancel(true);
                }
                SteveMod.LOGGER.info("Steve '{}' planning cancelled by stop", steve.getSteveName());
                AgentDebugBuffer.log(steve.getSteveName(), "PLAN", "planning cancelled by stop command");
                if (steve.level().isClientSide()) {
                    sendToGUI(steve.getSteveName(), "Planning cancelled.");
                }
                resetPlanningStateLocked();
                activePlanningRequestId = ++planningRequestSequence;
            }
        }
    }

    /**
     * Clears all planning-related state under planningLock. Does not touch
     * the request-id generator; callers must bump activePlanningRequestId
     * separately when invalidating the current request.
     */
    private void resetPlanningStateLocked() {
        isPlanning = false;
        planningFuture = null;
        pendingCommand = null;
        planningStartTick = -1;
    }

    /**
     * Puts the Steve in (or out of) "stay in place" mode. While staying,
     * no idle-follow is started and the Steve does not move; any new
     * command wakes it up automatically.
     */
    public void setStaying(boolean staying) {
        this.staying = staying;
        if (staying) {
            steve.getNavigation().stop();
        }
    }

    public boolean isStaying() {
        return staying;
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    /**
     * Checks if the agent is currently planning (async LLM call in progress).
     *
     * @return true if planning
     */
    public boolean isPlanning() {
        synchronized (planningLock) {
            return isPlanning;
        }
    }

    /**
     * Test-only hook that injects a planning future without starting a real LLM call.
     * Package-private so unit tests in the same package can set up a stuck-planning scenario.
     */
    void setPlanningFutureForTest(Future<ResponseParser.ParsedResponse> future, String command) {
        synchronized (planningLock) {
            this.pendingCommand = command;
            this.isPlanning = true;
            this.planningFuture = future;
            this.planningStartTick = this.ticksSinceLastAction;
            this.activePlanningRequestId = ++planningRequestSequence;
        }
    }

    /**
     * Number of tasks waiting in the queue.
     */
    public int getQueuedTaskCount() {
        return taskQueue.size();
    }

    /**
     * Description of the currently running action, or null if idle.
     */
    public String getCurrentActionDescription() {
        return currentAction != null ? currentAction.getDescription() : null;
    }

    /**
     * One-line summary of the agent state, used by /steve debug.
     */
    public String getStateSummary() {
        String summary;
        synchronized (planningLock) {
            if (isPlanning) {
                summary = "planning (" + (pendingCommand != null ? truncate(pendingCommand, 50) : "?") + ")";
            } else {
                summary = null;
            }
        }
        if (summary != null) {
            return summary;
        }
        if (currentAction != null) {
            return "executing: " + truncate(currentAction.getDescription(), 60)
                + " (queue: " + taskQueue.size() + ")";
        }
        if (!taskQueue.isEmpty()) {
            return "waiting, " + taskQueue.size() + " tasks queued";
        }
        return "idle (following player)";
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
