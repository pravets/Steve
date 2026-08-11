package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.async.AsyncLLMClient;
import com.steve.ai.llm.async.LLMCache;
import com.steve.ai.llm.async.LLMResponse;
import com.steve.ai.llm.async.OpenAICompatibleClient;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;
import com.steve.ai.memory.WorldKnowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TaskPlanner {

    private final AsyncLLMClient llmClient;
    private final LLMCache llmCache;
    private final OpenAICompatibleClient baseClient;

    public TaskPlanner() {
        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        String baseUrl = SteveConfig.LLM_BASE_URL.get();
        String apiKey = SteveConfig.LLM_API_KEY.get();
        String model = SteveConfig.LLM_MODEL.get();
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();
        boolean jsonMode = SteveConfig.LLM_JSON_MODE.get();
        int timeoutSeconds = SteveConfig.LLM_TIMEOUT_SECONDS.get();

        if (!LLMProviders.isValid(provider)) {
            SteveMod.LOGGER.warn("Unknown LLM provider '{}', falling back to 'ollama'. Valid: {}",
                provider, String.join(", ", List.of(
                    LLMProviders.OPENAI, LLMProviders.GROQ, LLMProviders.GEMINI,
                    LLMProviders.OLLAMA, LLMProviders.LMSTUDIO, LLMProviders.OPENCODE_GO,
                    LLMProviders.CUSTOM)));
            provider = LLMProviders.OLLAMA;
        }

        this.baseClient = OpenAICompatibleClient.forProvider(
            provider, baseUrl, apiKey, model, maxTokens, temperature, jsonMode, timeoutSeconds);

        if (LLMProviders.requiresKey(provider) && !baseClient.hasApiKey()) {
            SteveMod.LOGGER.warn("Provider '{}' requires an API key but llm.apiKey is empty. " +
                "LLM calls will fail; set the key in config/steve-common.toml.", provider);
        }

        this.llmCache = new LLMCache();
        this.llmClient = new ResilientLLMClient(baseClient, llmCache, new LLMFallbackHandler());

        SteveMod.LOGGER.info("TaskPlanner initialized: provider={}, baseUrl={}, model={}, jsonMode={}",
            provider, baseClient.getBaseUrl(), baseClient.getModel(), jsonMode);
    }

    /**
     * Asynchronously plans tasks for Steve using the configured LLM provider.
     *
     * <p>Returns immediately with a CompletableFuture; the LLM call runs on a
     * separate thread with resilience patterns (circuit breaker, retry, rate
     * limiting, caching, fallback).</p>
     */
    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(SteveEntity steve, String command) {
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);

            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("[Async] Requesting AI plan for Steve '{}' using {}: {}",
                steve.getSteveName(), provider, command);

            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", SteveConfig.LLM_MODEL.get(),
                "maxTokens", SteveConfig.MAX_TOKENS.get(),
                "temperature", SteveConfig.TEMPERATURE.get()
            );

            return llmClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        SteveMod.LOGGER.error("[Async] Empty response from LLM");
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        SteveMod.LOGGER.error("[Async] Failed to parse AI response");
                        return null;
                    }

                    SteveMod.LOGGER.info("[Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        parsed.getPlan(),
                        parsed.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return parsed;
                })
                .exceptionally(throwable -> {
                    SteveMod.LOGGER.error("[Async] Error planning tasks: {}", throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            SteveMod.LOGGER.error("[Async] Error setting up task planning", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Legacy blocking variant. Blocks the calling thread up to the configured
     * LLM timeout. Prefer {@link #planTasksAsync(SteveEntity, String)}.
     *
     * @deprecated Use planTasksAsync instead.
     */
    @Deprecated
    public ResponseParser.ParsedResponse planTasks(SteveEntity steve, String command) {
        try {
            return planTasksAsync(steve, command).get(SteveConfig.LLM_TIMEOUT_SECONDS.get() + 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error planning tasks (sync)", e);
            return null;
        }
    }

    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the configured provider's async client is healthy.
     */
    public boolean isProviderHealthy() {
        return llmClient.isHealthy();
    }

    /**
     * Live health check of the configured provider endpoint (GET /models).
     */
    public boolean pingProvider() {
        return getBaseClient().checkHealth();
    }

    private OpenAICompatibleClient getBaseClient() {
        return baseClient;
    }

    public String getActiveProvider() {
        return SteveConfig.AI_PROVIDER.get().toLowerCase();
    }

    public String getActiveModel() {
        String model = SteveConfig.LLM_MODEL.get();
        if (model == null || model.isEmpty()) {
            return LLMProviders.resolveModel(getActiveProvider(), "");
        }
        return model;
    }

    public String getActiveBaseUrl() {
        return LLMProviders.resolveBaseUrl(getActiveProvider(), SteveConfig.LLM_BASE_URL.get());
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();

        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "follow" -> task.hasParameters("player");
            case "gather" -> task.hasParameters("resource", "quantity");
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }
}
