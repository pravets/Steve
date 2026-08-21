package com.steve.ai.event;

import java.time.Instant;
import java.util.Map;

/**
 * Event published when an action starts execution.
 *
 * <p>Observers can use this for logging, metrics, or UI updates.</p>
 *
 * @since 1.1.0
 */
public class ActionStartedEvent {

    private final String agentId;
    private final String actionName;
    private final String description;
    private final Map<String, Object> parameters;
    private final Instant timestamp;

    /**
     * Constructs an ActionStartedEvent.
     *
     * @param agentId     Agent identifier
     * @param actionName  Name of the action (e.g., "mine", "build")
     * @param description Human-readable description
     * @param parameters  Action parameters
     */
    public ActionStartedEvent(String agentId, String actionName, String description, Map<String, Object> parameters) {
        this.agentId = agentId;
        this.actionName = actionName;
        this.description = description;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public String getActionName() {
        return actionName;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("ActionStartedEvent{agent='%s', action='%s', desc='%s'}",
            agentId, actionName, description);
    }
}
