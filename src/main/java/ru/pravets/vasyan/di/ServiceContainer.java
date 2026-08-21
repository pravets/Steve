package com.steve.ai.di;

import java.util.Optional;

/**
 * Lightweight Dependency Injection container interface.
 *
 * <p>Provides a simple service locator pattern for dependency injection
 * without requiring heavy frameworks like Spring or Guice. This keeps
 * the mod lightweight while enabling testability and decoupling.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * ServiceContainer container = new SimpleServiceContainer();
 *
 * // Register services
 * container.register(LLMCache.class, new LLMCache());
 * container.register(EventBus.class, new SimpleEventBus());
 *
 * // Retrieve services
 * LLMCache cache = container.getService(LLMCache.class);
 *
 * // Optional retrieval (safe)
 * Optional&lt;EventBus&gt; bus = container.findService(EventBus.class);
 * </pre>
 *
 * <p><b>Design Patterns:</b></p>
 * <ul>
 *   <li><b>Service Locator</b>: Central registry for service lookup</li>
 *   <li><b>Dependency Injection</b>: Enables loose coupling</li>
 *   <li><b>Interface Segregation</b>: Clean interface for DI</li>
 * </ul>
 *
 * @since 1.1.0
 * @see SimpleServiceContainer
 */
public interface ServiceContainer {

    /**
     * Registers a service instance with the container.
     *
     * <p>If a service of the same type is already registered, it will be replaced.</p>
     *
     * @param serviceType Service interface or class
     * @param instance    Service implementation instance
     * @param <T>         Service type
     * @throws IllegalArgumentException if serviceType or instance is null
     */
    <T> void register(Class<T> serviceType, T instance);

    /**
     * Registers a service with a custom name (for multiple implementations).
     *
     * @param name     Unique service name
     * @param instance Service implementation instance
     * @param <T>      Service type
     * @throws IllegalArgumentException if name or instance is null
     */
    <T> void register(String name, T instance);

    /**
     * Retrieves a service by type.
     *
     * @param serviceType Service interface or class
     * @param <T>         Service type
     * @return Service instance
     * @throws ServiceNotFoundException if service is not registered
     */
    <T> T getService(Class<T> serviceType);

    /**
     * Retrieves a service by name.
     *
     * @param name Service name
     * @param type Expected type for casting
     * @param <T>  Service type
     * @return Service instance
     * @throws ServiceNotFoundException if service is not registered
     */
    <T> T getService(String name, Class<T> type);

    /**
     * Safely retrieves a service by type, returning Optional.
     *
     * @param serviceType Service interface or class
     * @param <T>         Service type
     * @return Optional containing service, or empty if not found
     */
    <T> Optional<T> findService(Class<T> serviceType);

    /**
     * Safely retrieves a service by name, returning Optional.
     *
     * @param name Service name
     * @param type Expected type for casting
     * @param <T>  Service type
     * @return Optional containing service, or empty if not found
     */
    <T> Optional<T> findService(String name, Class<T> type);

    /**
     * Checks if a service is registered.
     *
     * @param serviceType Service interface or class
     * @return true if registered
     */
    boolean hasService(Class<?> serviceType);

    /**
     * Checks if a named service is registered.
     *
     * @param name Service name
     * @return true if registered
     */
    boolean hasService(String name);

    /**
     * Unregisters a service by type.
     *
     * @param serviceType Service interface or class
     * @return true if service was registered and removed
     */
    boolean unregister(Class<?> serviceType);

    /**
     * Unregisters a service by name.
     *
     * @param name Service name
     * @return true if service was registered and removed
     */
    boolean unregister(String name);

    /**
     * Clears all registered services.
     */
    void clear();

    /**
     * Returns the number of registered services.
     *
     * @return Service count
     */
    int getServiceCount();

    /**
     * Exception thrown when a requested service is not found.
     */
    class ServiceNotFoundException extends RuntimeException {
        public ServiceNotFoundException(String message) {
            super(message);
        }

        public ServiceNotFoundException(Class<?> serviceType) {
            super("Service not found: " + serviceType.getName());
        }

        public ServiceNotFoundException(String name, Class<?> type) {
            super("Service not found: " + name + " (type: " + type.getName() + ")");
        }
    }
}
