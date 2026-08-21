package com.steve.ai.execution;

import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.action.ActionResult;
import com.steve.ai.event.ActionCompletedEvent;
import com.steve.ai.event.ActionStartedEvent;
import com.steve.ai.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interceptor that publishes action lifecycle events to the EventBus.
 *
 * <p>Enables decoupled observation of action execution through the
 * pub-sub pattern. Other components can subscribe to ActionStartedEvent
 * and ActionCompletedEvent without coupling to the action execution.</p>
 *
 * @since 1.1.0
 */
public class EventPublishingInterceptor implements ActionInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventPublishingInterceptor.class);

    private final EventBus eventBus;
    private final String agentId;

    /**
     * Tracks start times for duration calculation.
     */
    private final ConcurrentHashMap<Integer, Long> startTimes;

    /**
     * Constructs an EventPublishingInterceptor.
     *
     * @param eventBus EventBus to publish to
     * @param agentId  Agent identifier for events
     */
    public EventPublishingInterceptor(EventBus eventBus, String agentId) {
        this.eventBus = eventBus;
        this.agentId = agentId;
        this.startTimes = new ConcurrentHashMap<>();
    }

    @Override
    public boolean beforeAction(BaseAction action, ActionContext context) {
        // Record start time
        startTimes.put(System.identityHashCode(action), System.currentTimeMillis());

        // Publish ActionStartedEvent
        ActionStartedEvent event = new ActionStartedEvent(
            agentId,
            extractActionName(action),
            action.getDescription(),
            Map.of() // Could extract parameters if needed
        );

        eventBus.publish(event);
        LOGGER.debug("Published ActionStartedEvent: {}", action.getDescription());

        return true;
    }

    @Override
    public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        // Calculate duration
        Long startTime = startTimes.remove(System.identityHashCode(action));
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        // Publish ActionCompletedEvent
        ActionCompletedEvent event = new ActionCompletedEvent(
            agentId,
            extractActionName(action),
            result.isSuccess(),
            result.getMessage(),
            duration
        );

        eventBus.publish(event);
        LOGGER.debug("Published ActionCompletedEvent: {} (success: {}, duration: {}ms)",
            action.getDescription(), result.isSuccess(), duration);
    }

    @Override
    public boolean onError(BaseAction action, Exception exception, ActionContext context) {
        // Calculate duration
        Long startTime = startTimes.remove(System.identityHashCode(action));
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        // Publish failed completion event
        ActionCompletedEvent event = new ActionCompletedEvent(
            agentId,
            extractActionName(action),
            false,
            "Exception: " + exception.getMessage(),
            duration
        );

        eventBus.publish(event);
        return false;
    }

    @Override
    public int getPriority() {
        return 500; // Medium-high priority
    }

    @Override
    public String getName() {
        return "EventPublishingInterceptor";
    }

    /**
     * Extracts action name from action class.
     */
    private String extractActionName(BaseAction action) {
        String className = action.getClass().getSimpleName();
        if (className.endsWith("Action")) {
            return className.substring(0, className.length() - 6).toLowerCase();
        }
        return className.toLowerCase();
    }
}
