package ru.pravets.vasyan.llm.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LRU cache for LLM responses.
 *
 * <p>Caches LLM responses to reduce API calls and costs. Cache key is a SHA-256 hash
 * of the combination of provider, model, and prompt.</p>
 *
 * <p>Pure JDK implementation (LinkedHashMap + MessageDigest) so the mod needs no
 * external cache dependencies in the jar.</p>
 *
 * <p><b>Cache Configuration:</b></p>
 * <ul>
 *   <li>Maximum size: 500 entries (~25MB estimated)</li>
 *   <li>TTL: 5 minutes (expireAfterWrite)</li>
 *   <li>Eviction: LRU (Least Recently Used)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All public methods are synchronized.</p>
 */
public class LLMCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCache.class);

    private static final int MAX_CACHE_SIZE = 500;
    private static final long TTL_MILLIS = 5 * 60 * 1000L; // 5 minutes

    private static final class Entry {
        final LLMResponse response;
        final long createdAt;

        Entry(LLMResponse response) {
            this.response = response;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final LinkedHashMap<String, Entry> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    /**
     * Constructs a new LLMCache with default configuration.
     */
    public LLMCache() {
        LOGGER.info("Initializing LLM cache (max size: {}, TTL: {} minutes)", MAX_CACHE_SIZE, TTL_MILLIS / 60000);

        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                boolean remove = size() > MAX_CACHE_SIZE;
                if (remove) {
                    evictions.incrementAndGet();
                }
                return remove;
            }
        };
    }

    /**
     * Retrieves a cached response if available.
     *
     * @param prompt     The prompt text (used in cache key)
     * @param model      The model name (used in cache key)
     * @param providerId The provider ID (used in cache key)
     * @return Optional containing cached response, or empty if cache miss
     */
    public synchronized Optional<LLMResponse> get(String prompt, String model, String providerId) {
        String key = generateKey(prompt, model, providerId);
        Entry entry = cache.get(key);

        if (entry == null) {
            misses.incrementAndGet();
            LOGGER.debug("Cache MISS for provider={}, model={}, promptHash={}", providerId, model, key.substring(0, 8));
            return Optional.empty();
        }

        // TTL check - lazy expiration
        if (System.currentTimeMillis() - entry.createdAt > TTL_MILLIS) {
            cache.remove(key);
            misses.incrementAndGet();
            LOGGER.debug("Cache EXPIRED for provider={}, model={}, promptHash={}", providerId, model, key.substring(0, 8));
            return Optional.empty();
        }

        hits.incrementAndGet();
        LOGGER.debug("Cache HIT for provider={}, model={}, promptHash={}", providerId, model, key.substring(0, 8));
        return Optional.of(entry.response);
    }

    /**
     * Stores a response in the cache.
     *
     * @param prompt     The prompt text (used in cache key)
     * @param model      The model name (used in cache key)
     * @param providerId The provider ID (used in cache key)
     * @param response   The response to cache
     */
    public synchronized void put(String prompt, String model, String providerId, LLMResponse response) {
        String key = generateKey(prompt, model, providerId);
        cache.put(key, new Entry(response.withCacheFlag(true)));
        LOGGER.debug("Cached response for provider={}, model={}, promptHash={}, tokens={}",
            providerId, model, key.substring(0, 8), response.getTokensUsed());
    }

    /**
     * Generates a cache key from prompt, model, and provider.
     *
     * <p>Uses SHA-256 hash to ensure consistent key length and prevent cache
     * key collision. Format: "{providerId}:{model}:{prompt}" → SHA-256 hex</p>
     */
    private String generateKey(String prompt, String model, String providerId) {
        String composite = providerId + ":" + model + ":" + prompt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(composite.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK; fall back to identity hash (dev environments only)
            LOGGER.warn("SHA-256 unavailable, using fallback cache key", e);
            return Integer.toHexString(composite.hashCode());
        }
    }

    /**
     * Returns the approximate number of entries in the cache.
     */
    public synchronized long size() {
        return cache.size();
    }

    /**
     * Invalidates all entries in the cache.
     */
    public synchronized void clear() {
        cache.clear();
        LOGGER.info("Cache cleared");
    }

    /**
     * Logs current cache statistics at INFO level.
     */
    public synchronized void logStats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        double hitRate = (hitCount + missCount) > 0 ? (double) hitCount / (hitCount + missCount) : 0.0;
        LOGGER.info("LLM Cache Stats - Size: ~{}/{}, Hit Rate: {:.2f}%, Hits: {}, Misses: {}, Evictions: {}",
            size(), MAX_CACHE_SIZE, hitRate * 100, hitCount, missCount, evictions.get());
    }

    /**
     * Returns the approximate number of entries in the cache.
     */
    public long estimatedSize() {
        return size();
    }
}
