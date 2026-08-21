package com.steve.ai.execution;

import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.action.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interceptor for logging action lifecycle events.
 *
 * <p>Logs action start, completion, and errors at appropriate levels.
 * High priority ensures it runs first for before and last for after.</p>
 *
 * @since 1.1.0
 */
public class LoggingInterceptor implements ActionInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public boolean beforeAction(BaseAction action, ActionContext context) {
        LOGGER.info("[ACTION START] {}", action.getDescription());
        return true;
    }

    @Override
    public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        if (result.isSuccess()) {
            LOGGER.info("[ACTION COMPLETE] {} - Success: {}",
                action.getDescription(), result.getMessage());
        } else {
            LOGGER.warn("[ACTION FAILED] {} - Reason: {}",
                action.getDescription(), result.getMessage());
        }
    }

    @Override
    public boolean onError(BaseAction action, Exception exception, ActionContext context) {
        LOGGER.error("[ACTION ERROR] {} - Exception: {}",
            action.getDescription(), exception.getMessage(), exception);
        return false; // Don't suppress exceptions
    }

    @Override
    public int getPriority() {
        return 1000; // Highest priority - runs first/last
    }

    @Override
    public String getName() {
        return "LoggingInterceptor";
    }
}
