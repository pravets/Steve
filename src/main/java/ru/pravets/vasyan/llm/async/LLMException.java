package com.steve.ai.llm.async;

/**
 * Exception thrown when LLM (Large Language Model) operations fail.
 *
 * <p>Provides typed error handling with {@link ErrorType} enum for different failure scenarios.
 * The {@code retryable} flag indicates whether the operation can be safely retried using
 * exponential backoff.</p>
 *
 * <p><b>Design Pattern:</b> Typed exceptions for precise error handling</p>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * try {
 *     LLMResponse response = client.sendAsync(prompt, params).get();
 * } catch (LLMException e) {
 *     if (e.isRetryable()) {
 *         // Retry logic with exponential backoff
 *         retryWithBackoff(prompt, params);
 *     } else {
 *         // Non-retryable error - log and fallback
 *         logger.error("LLM call failed permanently: " + e.getErrorType(), e);
 *         return fallbackHandler.generateFallback(prompt, e);
 *     }
 * }
 * </pre>
 *
 * @since 1.1.0
 */
public class LLMException extends RuntimeException {

    /**
     * Enumeration of LLM error types.
     *
     * <p>Each type indicates a specific failure scenario and whether retry is recommended.</p>
     */
    public enum ErrorType {
        /**
         * Rate limit exceeded (HTTP 429).
         * <p><b>Retryable:</b> Yes (with exponential backoff)</p>
         * <p><b>Cause:</b> Too many requests sent to provider API within time window</p>
         * <p><b>Solution:</b> Wait and retry; rate limiter should prevent this</p>
         */
        RATE_LIMIT(true),

        /**
         * Request timeout (no response within deadline).
         * <p><b>Retryable:</b> Yes</p>
         * <p><b>Cause:</b> Provider slow to respond or network latency</p>
         * <p><b>Solution:</b> Retry with same or increased timeout</p>
         */
        TIMEOUT(true),

        /**
         * Circuit breaker is OPEN (provider experiencing failures).
         * <p><b>Retryable:</b> No (circuit must close first)</p>
         * <p><b>Cause:</b> Circuit breaker detected high failure rate</p>
         * <p><b>Solution:</b> Use fallback; circuit will auto-close after wait duration</p>
         */
        CIRCUIT_OPEN(false),

        /**
         * Invalid or unparseable response from provider.
         * <p><b>Retryable:</b> No</p>
         * <p><b>Cause:</b> Malformed JSON, missing expected fields, or API contract change</p>
         * <p><b>Solution:</b> Check provider API documentation; may need code update</p>
         */
        INVALID_RESPONSE(false),

        /**
         * Network error (connection refused, DNS failure, etc.).
         * <p><b>Retryable:</b> Yes</p>
         * <p><b>Cause:</b> Network connectivity issue or provider downtime</p>
         * <p><b>Solution:</b> Retry; if persists, circuit breaker will open</p>
         */
        NETWORK_ERROR(true),

        /**
         * Authentication failure (invalid API key).
         * <p><b>Retryable:</b> No</p>
         * <p><b>Cause:</b> Invalid, expired, or revoked API key</p>
         * <p><b>Solution:</b> Update API key in configuration</p>
         */
        AUTH_ERROR(false),

        /**
         * Provider server error (HTTP 500-599).
         * <p><b>Retryable:</b> Yes</p>
         * <p><b>Cause:</b> Provider internal server error</p>
         * <p><b>Solution:</b> Retry; provider may recover</p>
         */
        SERVER_ERROR(true),

        /**
         * Client error (HTTP 400-499, excluding 429).
         * <p><b>Retryable:</b> No</p>
         * <p><b>Cause:</b> Invalid request parameters or prompt</p>
         * <p><b>Solution:</b> Fix request parameters; retrying won't help</p>
         */
        CLIENT_ERROR(false);

        private final boolean retryable;

        ErrorType(boolean retryable) {
            this.retryable = retryable;
        }

        /**
         * Returns whether this error type can be retried.
         *
         * @return true if retry recommended, false otherwise
         */
        public boolean isRetryable() {
            return retryable;
        }
    }

    private final ErrorType errorType;
    private final String providerId;
    private final boolean retryable;

    /**
     * Constructs a new LLMException.
     *
     * @param message    Error message
     * @param errorType  Typed error category
     * @param providerId Provider that threw the error ("openai", "groq", "gemini")
     * @param retryable  Whether the operation can be retried
     */
    public LLMException(String message, ErrorType errorType, String providerId, boolean retryable) {
        super(message);
        this.errorType = errorType;
        this.providerId = providerId;
        this.retryable = retryable;
    }

    /**
     * Constructs a new LLMException with a cause.
     *
     * @param message    Error message
     * @param errorType  Typed error category
     * @param providerId Provider that threw the error
     * @param retryable  Whether the operation can be retried
     * @param cause      The underlying exception
     */
    public LLMException(String message, ErrorType errorType, String providerId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.providerId = providerId;
        this.retryable = retryable;
    }

    /**
     * Returns the error type.
     *
     * @return The typed error category
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * Returns the provider ID that threw this exception.
     *
     * @return Provider ID ("openai", "groq", "gemini")
     */
    public String getProviderId() {
        return providerId;
    }

    /**
     * Returns whether this error is retryable.
     *
     * <p>This is a convenience method that checks {@code errorType.isRetryable()}.</p>
     *
     * @return true if retry recommended, false otherwise
     */
    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return "LLMException{" +
            "errorType=" + errorType +
            ", providerId='" + providerId + '\'' +
            ", retryable=" + retryable +
            ", message='" + getMessage() + '\'' +
            '}';
    }
}
