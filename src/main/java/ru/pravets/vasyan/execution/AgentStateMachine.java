package com.steve.ai.execution;

import com.steve.ai.event.EventBus;
import com.steve.ai.event.StateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * State machine for managing agent execution states.
 *
 * <p>Implements the State Pattern with explicit transition validation.
 * Invalid transitions are rejected and logged. State changes publish
 * events to the EventBus for observers.</p>
 *
 * <p><b>Thread Safety:</b> Uses AtomicReference for thread-safe state updates.</p>
 *
 * <p><b>Valid State Transitions:</b></p>
 * <ul>
 *   <li>IDLE → PLANNING (new command received)</li>
 *   <li>PLANNING → EXECUTING (planning complete)</li>
 *   <li>PLANNING → FAILED (planning error)</li>
 *   <li>EXECUTING → COMPLETED (all tasks done)</li>
 *   <li>EXECUTING → FAILED (execution error)</li>
 *   <li>EXECUTING → PAUSED (user pause request)</li>
 *   <li>PAUSED → EXECUTING (resume)</li>
 *   <li>PAUSED → IDLE (cancel)</li>
 *   <li>COMPLETED → IDLE (ready for next command)</li>
 *   <li>FAILED → IDLE (reset after error)</li>
 * </ul>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * AgentStateMachine sm = new AgentStateMachine(eventBus);
 *
 * // Transition states
 * sm.transitionTo(AgentState.PLANNING);  // IDLE → PLANNING
 * sm.transitionTo(AgentState.EXECUTING); // PLANNING → EXECUTING
 * sm.transitionTo(AgentState.COMPLETED); // EXECUTING → COMPLETED
 *
 * // Check current state
 * if (sm.getCurrentState() == AgentState.IDLE) {
 *     // Ready for new command
 * }
 *
 * // Check if transition is valid
 * if (sm.canTransitionTo(AgentState.EXECUTING)) {
 *     sm.transitionTo(AgentState.EXECUTING);
 * }
 * </pre>
 *
 * @since 1.1.0
 * @see AgentState
 * @see StateTransitionEvent
 */
public class AgentStateMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentStateMachine.class);

    /**
     * Defines valid transitions from each state.
     */
    private static final Map<AgentState, Set<AgentState>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(AgentState.class);

        // IDLE can go to PLANNING (new command)
        VALID_TRANSITIONS.put(AgentState.IDLE,
            EnumSet.of(AgentState.PLANNING));

        // PLANNING can go to EXECUTING (success) or FAILED (error)
        VALID_TRANSITIONS.put(AgentState.PLANNING,
            EnumSet.of(AgentState.EXECUTING, AgentState.FAILED, AgentState.IDLE));

        // EXECUTING can complete, fail, or pause
        VALID_TRANSITIONS.put(AgentState.EXECUTING,
            EnumSet.of(AgentState.COMPLETED, AgentState.FAILED, AgentState.PAUSED));

        // PAUSED can resume or cancel
        VALID_TRANSITIONS.put(AgentState.PAUSED,
            EnumSet.of(AgentState.EXECUTING, AgentState.IDLE));

        // COMPLETED goes back to IDLE
        VALID_TRANSITIONS.put(AgentState.COMPLETED,
            EnumSet.of(AgentState.IDLE));

        // FAILED can go back to IDLE (reset)
        VALID_TRANSITIONS.put(AgentState.FAILED,
            EnumSet.of(AgentState.IDLE));
    }

    /**
     * Current state (thread-safe).
     */
    private final AtomicReference<AgentState> currentState;

    /**
     * Event bus for publishing state change events.
     */
    private final EventBus eventBus;

    /**
     * Agent identifier for logging.
     */
    private final String agentId;

    /**
     * Constructs a state machine starting in IDLE state.
     *
     * @param eventBus Event bus for state change notifications (can be null)
     */
    public AgentStateMachine(EventBus eventBus) {
        this(eventBus, "default");
    }

    /**
     * Constructs a state machine with agent identifier.
     *
     * @param eventBus Event bus for state change notifications (can be null)
     * @param agentId  Agent identifier for logging
     */
    public AgentStateMachine(EventBus eventBus, String agentId) {
        this.currentState = new AtomicReference<>(AgentState.IDLE);
        this.eventBus = eventBus;
        this.agentId = agentId;
        LOGGER.debug("[{}] State machine initialized in IDLE state", agentId);
    }

    /**
     * Returns the current state.
     *
     * @return Current AgentState
     */
    public AgentState getCurrentState() {
        return currentState.get();
    }

    /**
     * Checks if transition to target state is valid.
     *
     * @param targetState Desired target state
     * @return true if transition is valid
     */
    public boolean canTransitionTo(AgentState targetState) {
        if (targetState == null) return false;

        AgentState current = currentState.get();
        Set<AgentState> validTargets = VALID_TRANSITIONS.get(current);

        return validTargets != null && validTargets.contains(targetState);
    }

    /**
     * Transitions to a new state if valid.
     *
     * <p>If transition is valid, publishes a StateTransitionEvent to the EventBus.</p>
     *
     * @param targetState Target state
     * @return true if transition was successful
     */
    public boolean transitionTo(AgentState targetState) {
        return transitionTo(targetState, null);
    }

    /**
     * Transitions to a new state with reason.
     *
     * @param targetState Target state
     * @param reason      Reason for transition (for logging/events)
     * @return true if transition was successful
     */
    public boolean transitionTo(AgentState targetState, String reason) {
        if (targetState == null) {
            LOGGER.warn("[{}] Cannot transition to null state", agentId);
            return false;
        }

        AgentState fromState = currentState.get();

        // Check if transition is valid
        if (!canTransitionTo(targetState)) {
            LOGGER.warn("[{}] Invalid state transition: {} → {} (allowed: {})",
                agentId, fromState, targetState, VALID_TRANSITIONS.get(fromState));
            return false;
        }

        // Atomic compare-and-set for thread safety
        if (currentState.compareAndSet(fromState, targetState)) {
            LOGGER.info("[{}] State transition: {} → {}{}",
                agentId, fromState, targetState,
                reason != null ? " (reason: " + reason + ")" : "");

            // Publish event
            if (eventBus != null) {
                eventBus.publish(new StateTransitionEvent(agentId, fromState, targetState, reason));
            }

            return true;
        } else {
            // State changed between get and compareAndSet (race condition)
            LOGGER.warn("[{}] State transition failed: concurrent modification", agentId);
            return false;
        }
    }

    /**
     * Forces a transition to a state, bypassing validation.
     *
     * <p><b>Warning:</b> Use only for recovery scenarios. Prefer transitionTo().</p>
     *
     * @param targetState Target state
     * @param reason      Reason for forced transition
     */
    public void forceTransition(AgentState targetState, String reason) {
        if (targetState == null) return;

        AgentState fromState = currentState.getAndSet(targetState);
        LOGGER.warn("[{}] FORCED state transition: {} → {} (reason: {})",
            agentId, fromState, targetState, reason);

        if (eventBus != null) {
            eventBus.publish(new StateTransitionEvent(agentId, fromState, targetState,
                "FORCED: " + reason));
        }
    }

    /**
     * Resets the state machine to IDLE.
     *
     * <p>Publishes a state transition event if state changes.</p>
     */
    public void reset() {
        AgentState previous = currentState.getAndSet(AgentState.IDLE);
        if (previous != AgentState.IDLE) {
            LOGGER.info("[{}] State machine reset: {} → IDLE", agentId, previous);
            if (eventBus != null) {
                eventBus.publish(new StateTransitionEvent(agentId, previous, AgentState.IDLE, "reset"));
            }
        }
    }

    /**
     * Checks if the agent can accept new commands.
     *
     * @return true if in IDLE, COMPLETED, or FAILED state
     */
    public boolean canAcceptCommands() {
        return currentState.get().canAcceptCommands();
    }

    /**
     * Checks if the agent is actively working.
     *
     * @return true if in PLANNING or EXECUTING state
     */
    public boolean isActive() {
        return currentState.get().isActive();
    }

    /**
     * Returns valid transitions from current state.
     *
     * @return Set of valid target states
     */
    public Set<AgentState> getValidTransitions() {
        Set<AgentState> valid = VALID_TRANSITIONS.get(currentState.get());
        return valid != null ? EnumSet.copyOf(valid) : EnumSet.noneOf(AgentState.class);
    }

    /**
     * Returns the agent ID.
     *
     * @return Agent identifier
     */
    public String getAgentId() {
        return agentId;
    }
}
