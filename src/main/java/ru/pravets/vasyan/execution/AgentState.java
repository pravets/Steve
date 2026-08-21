package com.steve.ai.execution;

/**
 * Enumeration of possible agent states in the state machine.
 *
 * <p>Follows the State Pattern to manage explicit state transitions.
 * Invalid transitions are prevented by the AgentStateMachine.</p>
 *
 * <p><b>State Transition Diagram:</b></p>
 * <pre>
 *                    ┌─────────────────────────────────────┐
 *                    │                                     │
 *                    ▼                                     │
 *   ┌──────────┐   ┌──────────┐   ┌───────────┐   ┌───────────┐
 *   │   IDLE   │──▶│ PLANNING │──▶│ EXECUTING │──▶│ COMPLETED │
 *   └──────────┘   └──────────┘   └───────────┘   └───────────┘
 *        ▲              │              │               │
 *        │              │              │               │
 *        │              ▼              ▼               │
 *        │         ┌──────────┐   ┌──────────┐        │
 *        │         │  FAILED  │   │  PAUSED  │        │
 *        │         └──────────┘   └──────────┘        │
 *        │              │              │               │
 *        └──────────────┴──────────────┴───────────────┘
 * </pre>
 *
 * @since 1.1.0
 * @see AgentStateMachine
 */
public enum AgentState {

    /**
     * Agent is idle, waiting for a command.
     * Can follow player, look around, etc.
     */
    IDLE("Idle", "Agent is waiting for commands"),

    /**
     * Agent is processing a command through the LLM.
     * Async planning is in progress.
     */
    PLANNING("Planning", "Processing command with AI"),

    /**
     * Agent is actively executing tasks.
     * Actions are being performed.
     */
    EXECUTING("Executing", "Performing actions"),

    /**
     * Agent execution is temporarily paused.
     * Can be resumed to EXECUTING state.
     */
    PAUSED("Paused", "Execution temporarily suspended"),

    /**
     * Agent has completed all tasks successfully.
     * Transitions back to IDLE automatically.
     */
    COMPLETED("Completed", "All tasks finished successfully"),

    /**
     * Agent encountered an error and stopped.
     * May require user intervention or retry.
     */
    FAILED("Failed", "Encountered an error");

    private final String displayName;
    private final String description;

    AgentState(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Returns the human-readable display name.
     *
     * @return Display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the state description.
     *
     * @return Description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this state allows receiving new commands.
     *
     * @return true if new commands can be processed
     */
    public boolean canAcceptCommands() {
        return this == IDLE || this == COMPLETED || this == FAILED;
    }

    /**
     * Checks if this state is a terminal state.
     *
     * @return true if terminal (COMPLETED or FAILED)
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * Checks if this state is an active state (doing work).
     *
     * @return true if actively working
     */
    public boolean isActive() {
        return this == PLANNING || this == EXECUTING;
    }
}
