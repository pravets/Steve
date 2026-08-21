package com.steve.ai.event;

import java.util.function.Consumer;

/**
 * Event bus interface for pub-sub messaging.
 *
 * <p>Implements the Observer Pattern for decoupled communication between
 * components. Publishers don't need to know about subscribers, enabling
 * loose coupling and extensibility.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * EventBus bus = new SimpleEventBus();
 *
 * // Subscribe to events
 * bus.subscribe(ActionStartedEvent.class, event -&gt; {
 *     System.out.println("Action started: " + event.getActionName());
 * });
 *
 * // Publish events
 * bus.publish(new ActionStartedEvent("mine", "Mining stone"));
 * </pre>
 *
 * <p><b>Design Patterns:</b></p>
 * <ul>
 *   <li><b>Observer</b>: Subscribers observe events from publishers</li>
 *   <li><b>Publish-Subscribe</b>: Decoupled message passing</li>
 * </ul>
 *
 * @since 1.1.0
 * @see SimpleEventBus
 */
public interface EventBus {

    /**
     * Subscribes to events of a specific type.
     *
     * <p>The subscriber will be notified whenever an event of the specified
     * type (or subtype) is published.</p>
     *
     * @param eventType  Event class to subscribe to
     * @param subscriber Consumer to receive events
     * @param <T>        Event type
     * @return Subscription handle for unsubscribing
     */
    <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber);

    /**
     * Subscribes with a priority (higher priority = called first).
     *
     * @param eventType  Event class to subscribe to
     * @param subscriber Consumer to receive events
     * @param priority   Subscriber priority (higher = called first)
     * @param <T>        Event type
     * @return Subscription handle for unsubscribing
     */
    <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber, int priority);

    /**
     * Publishes an event to all subscribers.
     *
     * <p>All subscribers for the event type (and its supertypes) will be notified
     * synchronously in priority order.</p>
     *
     * @param event Event to publish
     * @param <T>   Event type
     */
    <T> void publish(T event);

    /**
     * Publishes an event asynchronously.
     *
     * <p>Subscribers are notified on a separate thread. Errors in subscribers
     * don't affect the publisher.</p>
     *
     * @param event Event to publish
     * @param <T>   Event type
     */
    <T> void publishAsync(T event);

    /**
     * Unsubscribes all subscribers for an event type.
     *
     * @param eventType Event class
     */
    void unsubscribeAll(Class<?> eventType);

    /**
     * Clears all subscriptions.
     */
    void clear();

    /**
     * Returns the number of subscribers for an event type.
     *
     * @param eventType Event class
     * @return Subscriber count
     */
    int getSubscriberCount(Class<?> eventType);

    /**
     * Subscription handle for managing subscriptions.
     */
    interface Subscription {
        /**
         * Unsubscribes this subscription.
         */
        void unsubscribe();

        /**
         * Checks if this subscription is still active.
         *
         * @return true if active
         */
        boolean isActive();
    }
}
