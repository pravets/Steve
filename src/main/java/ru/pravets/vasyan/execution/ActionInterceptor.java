package com.steve.ai.execution;

import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.action.ActionResult;

/**
 * Interceptor interface for cross-cutting concerns in action execution.
 *
 * <p>Implements the Chain of Responsibility pattern, allowing multiple
 * interceptors to process action lifecycle events. Common uses include
 * logging, metrics, authorization, and event publishing.</p>
 *
 * <p><b>Lifecycle:</b></p>
 * <ol>
 *   <li>{@link #beforeAction(BaseAction, ActionContext)} - Before action starts</li>
 *   <li>Action executes (tick loop)</li>
 *   <li>{@link #afterAction(BaseAction, ActionResult, ActionContext)} - After action completes</li>
 *   <li>{@link #onError(BaseAction, Exception, ActionContext)} - If error occurs</li>
 * </ol>
 *
 * <p><b>Example Implementation:</b></p>
 * <pre>
 * public class LoggingInterceptor implements ActionInterceptor {
 *     &#64;Override
 *     public void beforeAction(BaseAction action, ActionContext context) {
 *         LOGGER.info("Starting action: {}", action.getDescription());
 *     }
 *
 *     &#64;Override
 *     public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
 *         LOGGER.info("Completed action: {} (success: {})",
 *             action.getDescription(), result.isSuccess());
 *     }
 *
 *     &#64;Override
 *     public int getPriority() { return 100; }
 * }
 * </pre>
 *
 * <p><b>Design Pattern:</b> Chain of Responsibility with priority ordering.</p>
 *
 * @since 1.1.0
 * @see InterceptorChain
 */
public interface ActionInterceptor {

    /**
     * Called before an action starts execution.
     *
     * <p>Return false to cancel the action (other interceptors won't be called).</p>
     *
     * @param action  Action about to start
     * @param context Action context
     * @return true to continue, false to cancel
     */
    default boolean beforeAction(BaseAction action, ActionContext context) {
        return true;
    }

    /**
     * Called after an action completes (success or failure).
     *
     * @param action  Completed action
     * @param result  Action result
     * @param context Action context
     */
    default void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        // Default: no-op
    }

    /**
     * Called when an action throws an exception.
     *
     * <p>Return true to suppress the exception (action will be marked as failed but no rethrow).
     * Return false to let the exception propagate.</p>
     *
     * @param action    Action that failed
     * @param exception Exception thrown
     * @param context   Action context
     * @return true to suppress exception, false to propagate
     */
    default boolean onError(BaseAction action, Exception exception, ActionContext context) {
        return false;
    }

    /**
     * Returns the interceptor priority.
     *
     * <p>Higher priority interceptors are called first in beforeAction,
     * and last in afterAction (stack order).</p>
     *
     * <p><b>Priority Guidelines:</b></p>
     * <ul>
     *   <li>1000+: Logging/tracing (always first/last)</li>
     *   <li>500-999: Metrics collection</li>
     *   <li>100-499: Event publishing</li>
     *   <li>0-99: Business logic interceptors</li>
     * </ul>
     *
     * @return Interceptor priority
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Returns the interceptor name for logging.
     *
     * @return Interceptor name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
