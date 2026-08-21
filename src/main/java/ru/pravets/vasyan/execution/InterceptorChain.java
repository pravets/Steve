package com.steve.ai.execution;

import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.action.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chain of interceptors for action execution lifecycle.
 *
 * <p>Manages a collection of ActionInterceptors and invokes them in priority
 * order. Follows the Chain of Responsibility pattern.</p>
 *
 * <p><b>Execution Order:</b></p>
 * <ul>
 *   <li>beforeAction: High priority → Low priority</li>
 *   <li>afterAction: Low priority → High priority (stack unwinding)</li>
 *   <li>onError: Low priority → High priority</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Uses CopyOnWriteArrayList for thread-safe iteration.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * InterceptorChain chain = new InterceptorChain();
 * chain.addInterceptor(new LoggingInterceptor());
 * chain.addInterceptor(new MetricsInterceptor());
 *
 * // Before action
 * if (chain.executeBeforeAction(action, context)) {
 *     action.start();
 *     // ... action executes
 *     chain.executeAfterAction(action, result, context);
 * }
 * </pre>
 *
 * @since 1.1.0
 * @see ActionInterceptor
 */
public class InterceptorChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterceptorChain.class);

    /**
     * List of interceptors sorted by priority.
     */
    private final CopyOnWriteArrayList<ActionInterceptor> interceptors;

    public InterceptorChain() {
        this.interceptors = new CopyOnWriteArrayList<>();
    }

    /**
     * Adds an interceptor to the chain.
     *
     * <p>The chain is automatically sorted by priority (descending).</p>
     *
     * @param interceptor Interceptor to add
     * @throws IllegalArgumentException if interceptor is null
     */
    public void addInterceptor(ActionInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("Interceptor cannot be null");
        }

        interceptors.add(interceptor);
        sortInterceptors();

        LOGGER.debug("Added interceptor: {} (priority: {})",
            interceptor.getName(), interceptor.getPriority());
    }

    /**
     * Removes an interceptor from the chain.
     *
     * @param interceptor Interceptor to remove
     * @return true if removed
     */
    public boolean removeInterceptor(ActionInterceptor interceptor) {
        boolean removed = interceptors.remove(interceptor);
        if (removed) {
            LOGGER.debug("Removed interceptor: {}", interceptor.getName());
        }
        return removed;
    }

    /**
     * Sorts interceptors by priority (descending).
     */
    private void sortInterceptors() {
        List<ActionInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(ActionInterceptor::getPriority).reversed());
        interceptors.clear();
        interceptors.addAll(sorted);
    }

    /**
     * Executes all beforeAction interceptors.
     *
     * <p>If any interceptor returns false, execution stops and returns false.</p>
     *
     * @param action  Action about to start
     * @param context Action context
     * @return true if all interceptors approved, false if any rejected
     */
    public boolean executeBeforeAction(BaseAction action, ActionContext context) {
        for (ActionInterceptor interceptor : interceptors) {
            try {
                if (!interceptor.beforeAction(action, context)) {
                    LOGGER.info("Action cancelled by interceptor: {}", interceptor.getName());
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} beforeAction: {}",
                    interceptor.getName(), e.getMessage(), e);
                // Continue to other interceptors
            }
        }
        return true;
    }

    /**
     * Executes all afterAction interceptors in reverse priority order.
     *
     * @param action  Completed action
     * @param result  Action result
     * @param context Action context
     */
    public void executeAfterAction(BaseAction action, ActionResult result, ActionContext context) {
        // Reverse order for stack unwinding
        List<ActionInterceptor> reversed = new ArrayList<>(interceptors);
        Collections.reverse(reversed);

        for (ActionInterceptor interceptor : reversed) {
            try {
                interceptor.afterAction(action, result, context);
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} afterAction: {}",
                    interceptor.getName(), e.getMessage(), e);
                // Continue to other interceptors
            }
        }
    }

    /**
     * Executes all onError interceptors.
     *
     * @param action    Failed action
     * @param exception Exception thrown
     * @param context   Action context
     * @return true if any interceptor suppressed the exception
     */
    public boolean executeOnError(BaseAction action, Exception exception, ActionContext context) {
        // Reverse order for error handling
        List<ActionInterceptor> reversed = new ArrayList<>(interceptors);
        Collections.reverse(reversed);

        boolean suppressed = false;
        for (ActionInterceptor interceptor : reversed) {
            try {
                if (interceptor.onError(action, exception, context)) {
                    LOGGER.debug("Exception suppressed by interceptor: {}", interceptor.getName());
                    suppressed = true;
                }
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} onError: {}",
                    interceptor.getName(), e.getMessage(), e);
            }
        }
        return suppressed;
    }

    /**
     * Clears all interceptors.
     */
    public void clear() {
        interceptors.clear();
        LOGGER.debug("InterceptorChain cleared");
    }

    /**
     * Returns the number of interceptors.
     *
     * @return Interceptor count
     */
    public int size() {
        return interceptors.size();
    }

    /**
     * Returns an unmodifiable view of interceptors.
     *
     * @return List of interceptors
     */
    public List<ActionInterceptor> getInterceptors() {
        return Collections.unmodifiableList(new ArrayList<>(interceptors));
    }
}
