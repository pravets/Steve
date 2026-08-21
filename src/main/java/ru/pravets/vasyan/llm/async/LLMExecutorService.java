package com.steve.ai.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton manager for LLM provider-specific thread pools.
 *
 * <p>Implements the <b>Bulkhead Pattern</b> by maintaining separate thread pools
 * for each LLM provider (OpenAI, Groq, Gemini). This ensures that failures or
 * slowdowns in one provider do not cascade to others.</p>
 *
 * <p><b>Thread Pool Configuration:</b></p>
 * <ul>
 *   <li>5 threads per provider (configurable)</li>
 *   <li>Named threads for easy debugging (e.g., "llm-openai-0", "llm-groq-1")</li>
 *   <li>Daemon threads (don't prevent JVM shutdown)</li>
 * </ul>
 *
 * <p><b>Design Patterns:</b></p>
 * <ul>
 *   <li>Singleton: Single instance manages all thread pools</li>
 *   <li>Bulkhead: Isolated thread pools prevent cascading failures</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b></p>
 * <pre>
 * // Initialization (automatic on first access)
 * ExecutorService executor = LLMExecutorService.getInstance().getExecutor("openai");
 *
 * // Shutdown (on server stop)
 * LLMExecutorService.getInstance().shutdown();
 * </pre>
 *
 * @since 1.1.0
 */
public class LLMExecutorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMExecutorService.class);
    private static final LLMExecutorService INSTANCE = new LLMExecutorService();

    private static final int THREADS_PER_PROVIDER = 5;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final ExecutorService openaiExecutor;
    private final ExecutorService groqExecutor;
    private final ExecutorService geminiExecutor;

    private volatile boolean isShutdown = false;

    /**
     * Private constructor for singleton pattern.
     * Initializes three separate thread pools for bulkhead isolation.
     */
    private LLMExecutorService() {
        LOGGER.info("Initializing LLM executor service with {} threads per provider", THREADS_PER_PROVIDER);

        this.openaiExecutor = Executors.newFixedThreadPool(
            THREADS_PER_PROVIDER,
            new NamedThreadFactory("llm-openai")
        );

        this.groqExecutor = Executors.newFixedThreadPool(
            THREADS_PER_PROVIDER,
            new NamedThreadFactory("llm-groq")
        );

        this.geminiExecutor = Executors.newFixedThreadPool(
            THREADS_PER_PROVIDER,
            new NamedThreadFactory("llm-gemini")
        );

        LOGGER.info("LLM executor service initialized successfully");
    }

    /**
     * Returns the singleton instance.
     *
     * @return The singleton LLMExecutorService instance
     */
    public static LLMExecutorService getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the executor for the specified provider.
     *
     * <p><b>Thread Safety:</b> This method is thread-safe.</p>
     *
     * @param providerId Provider identifier ("openai", "groq", "gemini")
     * @return The provider-specific ExecutorService
     * @throws IllegalArgumentException if providerId is unknown
     * @throws IllegalStateException if executor service has been shut down
     */
    public ExecutorService getExecutor(String providerId) {
        if (isShutdown) {
            throw new IllegalStateException("LLMExecutorService has been shut down");
        }

        return switch (providerId.toLowerCase()) {
            case "openai" -> openaiExecutor;
            case "groq" -> groqExecutor;
            case "gemini" -> geminiExecutor;
            default -> throw new IllegalArgumentException("Unknown provider: " + providerId);
        };
    }

    /**
     * Gracefully shuts down all thread pools.
     *
     * <p>Attempts graceful shutdown first (no new tasks, finish existing tasks),
     * then forces shutdown if graceful shutdown times out.</p>
     *
     * <p><b>Call this on server shutdown</b> to ensure clean resource cleanup.</p>
     *
     * <p><b>Thread Safety:</b> This method is thread-safe and idempotent
     * (calling multiple times is safe).</p>
     */
    public synchronized void shutdown() {
        if (isShutdown) {
            LOGGER.debug("LLMExecutorService already shut down, ignoring duplicate shutdown call");
            return;
        }

        LOGGER.info("Shutting down LLM executor service...");
        isShutdown = true;

        shutdownExecutor("openai", openaiExecutor);
        shutdownExecutor("groq", groqExecutor);
        shutdownExecutor("gemini", geminiExecutor);

        LOGGER.info("LLM executor service shut down successfully");
    }

    /**
     * Shuts down a single executor with timeout.
     *
     * @param providerId Provider name (for logging)
     * @param executor The executor to shut down
     */
    private void shutdownExecutor(String providerId, ExecutorService executor) {
        try {
            LOGGER.debug("Shutting down {} executor...", providerId);
            executor.shutdown();

            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("{} executor did not terminate gracefully, forcing shutdown", providerId);
                executor.shutdownNow();

                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.error("{} executor did not terminate after forced shutdown", providerId);
                }
            } else {
                LOGGER.debug("{} executor shut down gracefully", providerId);
            }
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while shutting down {} executor", providerId, e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns whether the executor service has been shut down.
     *
     * @return true if shut down, false otherwise
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * Custom ThreadFactory that creates named daemon threads.
     *
     * <p>Thread naming format: "{prefix}-{number}" (e.g., "llm-openai-0")</p>
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger threadNumber = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(true); // Don't prevent JVM shutdown
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
