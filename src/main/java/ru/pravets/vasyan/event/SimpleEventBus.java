package com.steve.ai.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thread-safe implementation of EventBus.
 *
 * <p>Uses CopyOnWriteArrayList for thread-safe subscriber management
 * and ExecutorService for async event publishing.</p>
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Priority-based subscriber ordering</li>
 *   <li>Synchronous and asynchronous publishing</li>
 *   <li>Error isolation (one subscriber's error doesn't affect others)</li>
 *   <li>Subscription handles for easy unsubscription</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All operations are thread-safe.</p>
 *
 * @since 1.1.0
 * @see EventBus
 */
public class SimpleEventBus implements EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleEventBus.class);

    /**
     * Map of event type to list of subscribers.
     */
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<SubscriberEntry<?>>> subscribers;

    /**
     * Executor for async event publishing.
     */
    private final ExecutorService asyncExecutor;

    /**
     * Constructs a SimpleEventBus with default async executor.
     */
    public SimpleEventBus() {
        this.subscribers = new ConcurrentHashMap<>();
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "event-bus-async");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Constructs a SimpleEventBus with custom executor.
     *
     * @param asyncExecutor Executor for async publishing
     */
    public SimpleEventBus(ExecutorService asyncExecutor) {
        this.subscribers = new ConcurrentHashMap<>();
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber) {
        return subscribe(eventType, subscriber, 0);
    }

    @Override
    public <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber, int priority) {
        if (eventType == null) {
            throw new IllegalArgumentException("Event type cannot be null");
        }
        if (subscriber == null) {
            throw new IllegalArgumentException("Subscriber cannot be null");
        }

        SubscriberEntry<T> entry = new SubscriberEntry<>(subscriber, priority);

        subscribers.compute(eventType, (key, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.add(entry);
            // Sort by priority (descending - higher priority first)
            list.sort((a, b) -> Integer.compare(b.priority, a.priority));
            return list;
        });

        LOGGER.debug("Subscribed to {} (priority: {}, total subscribers: {})",
            eventType.getSimpleName(), priority, getSubscriberCount(eventType));

        return new SubscriptionImpl(eventType, entry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        if (event == null) {
            LOGGER.warn("Cannot publish null event");
            return;
        }

        Class<?> eventType = event.getClass();
        CopyOnWriteArrayList<SubscriberEntry<?>> subs = subscribers.get(eventType);

        if (subs == null || subs.isEmpty()) {
            LOGGER.trace("No subscribers for event type: {}", eventType.getSimpleName());
            return;
        }

        LOGGER.debug("Publishing {} to {} subscribers", eventType.getSimpleName(), subs.size());

        for (SubscriberEntry<?> entry : subs) {
            if (!entry.isActive()) continue;

            try {
                ((Consumer<T>) entry.subscriber).accept(event);
            } catch (Exception e) {
                LOGGER.error("Error in event subscriber for {}: {}",
                    eventType.getSimpleName(), e.getMessage(), e);
                // Continue to other subscribers - don't let one failure stop others
            }
        }
    }

    @Override
    public <T> void publishAsync(T event) {
        if (event == null) return;

        asyncExecutor.submit(() -> {
            try {
                publish(event);
            } catch (Exception e) {
                LOGGER.error("Error in async event publishing: {}", e.getMessage(), e);
            }
        });
    }

    @Override
    public void unsubscribeAll(Class<?> eventType) {
        if (eventType == null) return;

        CopyOnWriteArrayList<SubscriberEntry<?>> removed = subscribers.remove(eventType);
        if (removed != null) {
            removed.forEach(entry -> entry.active.set(false));
            LOGGER.debug("Unsubscribed all {} subscribers from {}",
                removed.size(), eventType.getSimpleName());
        }
    }

    @Override
    public void clear() {
        subscribers.values().forEach(list ->
            list.forEach(entry -> entry.active.set(false)));
        subscribers.clear();
        LOGGER.info("EventBus cleared");
    }

    @Override
    public int getSubscriberCount(Class<?> eventType) {
        if (eventType == null) return 0;

        CopyOnWriteArrayList<SubscriberEntry<?>> subs = subscribers.get(eventType);
        if (subs == null) return 0;

        return (int) subs.stream().filter(SubscriberEntry::isActive).count();
    }

    /**
     * Shuts down the async executor.
     *
     * <p>Call this during application shutdown.</p>
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("EventBus shutdown complete");
    }

    /**
     * Internal subscriber entry with priority and active flag.
     */
    private static class SubscriberEntry<T> {
        final Consumer<T> subscriber;
        final int priority;
        final AtomicBoolean active;

        SubscriberEntry(Consumer<T> subscriber, int priority) {
            this.subscriber = subscriber;
            this.priority = priority;
            this.active = new AtomicBoolean(true);
        }

        boolean isActive() {
            return active.get();
        }
    }

    /**
     * Subscription implementation for unsubscribing.
     */
    private class SubscriptionImpl implements Subscription {
        private final Class<?> eventType;
        private final SubscriberEntry<?> entry;

        SubscriptionImpl(Class<?> eventType, SubscriberEntry<?> entry) {
            this.eventType = eventType;
            this.entry = entry;
        }

        @Override
        public void unsubscribe() {
            entry.active.set(false);
            CopyOnWriteArrayList<SubscriberEntry<?>> list = subscribers.get(eventType);
            if (list != null) {
                list.remove(entry);
            }
            LOGGER.debug("Unsubscribed from {}", eventType.getSimpleName());
        }

        @Override
        public boolean isActive() {
            return entry.isActive();
        }
    }
}
