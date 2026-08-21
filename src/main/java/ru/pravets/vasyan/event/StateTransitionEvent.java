package com.steve.ai.event;

import com.steve.ai.execution.AgentState;

import java.time.Instant;

/**
 * Event published when the agent state machine transitions between states.
 *
 * <p>Observers can subscribe to this event to react to state changes,
 * such as updating UI, logging, or triggering side effects.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * eventBus.subscribe(StateTransitionEvent.class, event -&gt; {
 *     if (event.getToState() == AgentState.FAILED) {
 *         alertUser("Agent encountered an error: " + event.getReason());
 *     }
 * });
 * </pre>
 *
 * @since 1.1.0
 * @see AgentState
 */
public class StateTransitionEvent {

    private final String agentId;
    private final AgentState fromState;
    private final AgentState toState;
    private final String reason;
    private final Instant timestamp;

    /**
     * Constructs a StateTransitionEvent.
     *
     * @param agentId   Agent identifier
     * @param fromState Previous state
     * @param toState   New state
     * @param reason    Reason for transition (can be null)
     */
    public StateTransitionEvent(String agentId, AgentState fromState, AgentState toState, String reason) {
        this.agentId = agentId;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public AgentState getFromState() {
        return fromState;
    }

    public AgentState getToState() {
        return toState;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("StateTransitionEvent{agent='%s', %s → %s, reason='%s'}",
            agentId, fromState, toState, reason);
    }
}
