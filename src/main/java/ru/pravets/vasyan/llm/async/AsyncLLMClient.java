package ru.pravets.vasyan.llm.async;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous interface for LLM (Large Language Model) clients.
 * Provides non-blocking API calls using CompletableFuture to prevent game thread blocking.
 *
 * <p>This interface is implemented by provider-specific clients (OpenAI, Groq, Gemini)
 * and wrapped by ResilientLLMClient for fault tolerance patterns.</p>
 *
 * <p><b>Design Pattern:</b> Strategy pattern for pluggable LLM providers</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * AsyncLLMClient client = new OpenAICompatibleClient(providerId, baseUrl, apiKey, model, maxTokens, temp, true, 60);
 * Map&lt;String, Object&gt; params = Map.of("model", "gpt-3.5-turbo", "maxTokens", 1000);
 *
 * client.sendAsync("Generate a task plan", params)
 *     .thenAccept(response -> {
 *         System.out.println("Received: " + response.getContent());
 *     })
 *     .exceptionally(throwable -> {
 *         System.err.println("LLM call failed: " + throwable.getMessage());
 *         return null;
 *     });
 * </pre>
 *
 * @see LLMResponse
 * @see LLMException
 * @since 1.1.0
 */
public interface AsyncLLMClient {

    /**
     * Sends an asynchronous request to the LLM provider.
     *
     * <p>This method returns immediately with a CompletableFuture, allowing the calling
     * thread (typically the game thread) to continue without blocking. The actual HTTP
     * request is executed on a provider-specific thread pool managed by LLMExecutorService.</p>
     *
     * <p><b>Thread Safety:</b> This method is thread-safe and can be called concurrently
     * from multiple threads. The underlying thread pool handles concurrency.</p>
     *
     * @param prompt   The text prompt to send to the LLM
     * @param params   Additional parameters for the request (model, maxTokens, temperature, etc.)
     *                 Expected keys: "model" (String), "maxTokens" (Integer), "temperature" (Double)
     * @return A CompletableFuture that will complete with the LLM response
     * @throws IllegalArgumentException if prompt is null or empty, or required params are missing
     * @see LLMResponse
     * @see LLMException
     */
    CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params);

    /**
     * Returns the unique identifier for this LLM provider.
     *
     * <p>Used for logging, metrics, and thread pool selection. Must be one of:
     * "openai", "groq", "gemini"</p>
     *
     * @return Provider ID (lowercase, e.g., "openai", "groq", "gemini")
     */
    String getProviderId();

    /**
     * Checks if the client is healthy and able to accept requests.
     *
     * <p>Returns false if the circuit breaker is OPEN, indicating the provider
     * is experiencing failures. When unhealthy, requests will be rejected
     * immediately without attempting the HTTP call.</p>
     *
     * <p><b>Use Case:</b> Health checks, load balancer decisions, monitoring dashboards</p>
     *
     * @return true if client is healthy (circuit breaker CLOSED or HALF_OPEN), false if circuit is OPEN
     */
    boolean isHealthy();
}
