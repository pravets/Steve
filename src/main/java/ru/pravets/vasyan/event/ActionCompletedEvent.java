package com.steve.ai.event;

import java.time.Instant;

/**
 * Event published when an action completes execution.
 *
 * <p>Contains information about the action result, including success/failure
 * status and execution duration.</p>
 *
 * @since 1.1.0
 */
public class ActionCompletedEvent {

    private final String agentId;
    private final String actionName;
    private final boolean success;
    private final String message;
    private final long durationMs;
    private final Instant timestamp;

    /**
     * Constructs an ActionCompletedEvent.
     *
     * @param agentId    Agent identifier
     * @param actionName Name of the action
     * @param success    Whether action succeeded
     * @param message    Result message
     * @param durationMs Execution duration in milliseconds
     */
    public ActionCompletedEvent(String agentId, String actionName, boolean success, String message, long durationMs) {
        this.agentId = agentId;
        this.actionName = actionName;
        this.success = success;
        this.message = message;
        this.durationMs = durationMs;
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public String getActionName() {
        return actionName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("ActionCompletedEvent{agent='%s', action='%s', success=%s, duration=%dms}",
            agentId, actionName, success, durationMs);
    }
}
