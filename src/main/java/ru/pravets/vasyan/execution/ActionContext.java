package com.steve.ai.execution;

import com.steve.ai.di.ServiceContainer;
import com.steve.ai.event.EventBus;

import java.util.Optional;

/**
 * Context object providing dependencies and services for action execution.
 *
 * <p>This class serves as a facade for all services and context information
 * that actions might need during execution. It follows the Context Object
 * pattern, providing a clean way to pass multiple dependencies without
 * long parameter lists.</p>
 *
 * <p><b>Design Patterns:</b></p>
 * <ul>
 *   <li><b>Context Object</b>: Encapsulates context for passing between layers</li>
 *   <li><b>Facade</b>: Simplifies access to complex subsystem</li>
 *   <li><b>Immutable</b>: Thread-safe after construction</li>
 * </ul>
 *
 * <p><b>Example Usage in ActionFactory:</b></p>
 * <pre>
 * ActionFactory factory = (steve, task, ctx) -&gt; {
 *     // Get services from context
 *     EventBus bus = ctx.getEventBus();
 *
 *     // Or use service container for custom services
 *     LLMCache cache = ctx.getService(LLMCache.class);
 *
 *     return new SmartMineAction(steve, task, cache, bus);
 * };
 * </pre>
 *
 * @since 1.1.0
 * @see ServiceContainer
 * @see EventBus
 * @see AgentStateMachine
 */
public class ActionContext {

    private final ServiceContainer serviceContainer;
    private final EventBus eventBus;
    private final AgentStateMachine stateMachine;
    private final InterceptorChain interceptorChain;

    /**
     * Constructs an ActionContext with all dependencies.
     *
     * @param serviceContainer Service container for dependency injection
     * @param eventBus         Event bus for pub-sub messaging
     * @param stateMachine     State machine for agent state management
     * @param interceptorChain Interceptor chain for cross-cutting concerns
     */
    public ActionContext(
            ServiceContainer serviceContainer,
            EventBus eventBus,
            AgentStateMachine stateMachine,
            InterceptorChain interceptorChain) {
        this.serviceContainer = serviceContainer;
        this.eventBus = eventBus;
        this.stateMachine = stateMachine;
        this.interceptorChain = interceptorChain;
    }

    /**
     * Returns the service container for dependency injection.
     *
     * @return ServiceContainer instance
     */
    public ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    /**
     * Returns the event bus for pub-sub messaging.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the state machine for agent state management.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for cross-cutting concerns.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Convenience method to get a service by type.
     *
     * @param serviceType Service class
     * @param <T>         Service type
     * @return Service instance
     * @throws ServiceContainer.ServiceNotFoundException if not found
     */
    public <T> T getService(Class<T> serviceType) {
        return serviceContainer.getService(serviceType);
    }

    /**
     * Convenience method to safely find a service by type.
     *
     * @param serviceType Service class
     * @param <T>         Service type
     * @return Optional containing service, or empty if not found
     */
    public <T> Optional<T> findService(Class<T> serviceType) {
        return serviceContainer.findService(serviceType);
    }

    /**
     * Convenience method to publish an event.
     *
     * @param event Event to publish
     * @param <T>   Event type
     */
    public <T> void publishEvent(T event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    /**
     * Convenience method to get current agent state.
     *
     * @return Current AgentState
     */
    public AgentState getCurrentState() {
        return stateMachine != null ? stateMachine.getCurrentState() : AgentState.IDLE;
    }

    /**
     * Builder for constructing ActionContext instances.
     */
    public static class Builder {
        private ServiceContainer serviceContainer;
        private EventBus eventBus;
        private AgentStateMachine stateMachine;
        private InterceptorChain interceptorChain;

        public Builder serviceContainer(ServiceContainer serviceContainer) {
            this.serviceContainer = serviceContainer;
            return this;
        }

        public Builder eventBus(EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder stateMachine(AgentStateMachine stateMachine) {
            this.stateMachine = stateMachine;
            return this;
        }

        public Builder interceptorChain(InterceptorChain interceptorChain) {
            this.interceptorChain = interceptorChain;
            return this;
        }

        public ActionContext build() {
            return new ActionContext(serviceContainer, eventBus, stateMachine, interceptorChain);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
