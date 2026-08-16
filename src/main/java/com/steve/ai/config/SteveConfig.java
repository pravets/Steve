package com.steve.ai.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SteveConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> LLM_MODEL;
    public static final ForgeConfigSpec.BooleanValue LLM_JSON_MODE;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue LLM_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_RADIUS;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_STEP;
    public static final ForgeConfigSpec.IntValue WORLD_SCAN_CACHE_TICKS;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;
    public static final ForgeConfigSpec.BooleanValue FORCE_LOAD_CHUNKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("LLM provider configuration. All providers use the OpenAI-compatible Chat Completions API.",
            "provider: openai | groq | gemini | ollama | lmstudio | opencode-go | custom",
            "  ollama     -> http://localhost:11434/v1 (no key needed)",
            "  lmstudio   -> http://localhost:1234/v1 (no key needed)",
            "  opencode-go-> https://opencode.ai/zen/go/v1 (key from OpenCode Zen, models like deepseek-v4-flash)",
            "  custom     -> any OpenAI-compatible endpoint, baseUrl is required")
            .push("llm");

        AI_PROVIDER = builder
            .comment("Active LLM provider")
            .define("provider", "ollama");

        LLM_BASE_URL = builder
            .comment("Base URL override. Empty = preset default (e.g. http://localhost:11434/v1 for ollama).",
                "Required for provider 'custom'.")
            .define("baseUrl", "");

        LLM_API_KEY = builder
            .comment("API key. Empty for ollama/lmstudio; required for openai/groq/gemini/opencode-go.")
            .define("apiKey", "");

        LLM_MODEL = builder
            .comment("Model name. Empty = preset default (deepseek-v4-flash for opencode-go, llama3.1 for ollama).",
                "For lmstudio leave empty to use whatever model is currently loaded.")
            .define("model", "");

        LLM_JSON_MODE = builder
            .comment("Send response_format: {\"type\":\"json_object\"}. Greatly improves JSON output reliability.",
                "Disable if your provider rejects this field.")
            .define("jsonMode", true);

        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);

        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);

        LLM_TIMEOUT_SECONDS = builder
            .comment("Per-request timeout in seconds")
            .defineInRange("timeoutSeconds", 60, 5, 300);

        builder.pop();

        builder.comment("Steve Vision (world perception) Configuration",
            "Steve scans the world around him to find blocks and entities. The scan is",
            "honest: a block is only seen if there is a clear line of sight (no cheats).",
            "Scans run on demand and results are cached for a few ticks.")
            .push("vision");

        WORLD_SCAN_RADIUS = builder
            .comment("Vision radius in blocks (how far Steve can see)")
            .defineInRange("scanRadius", 32, 8, 64);

        WORLD_SCAN_STEP = builder
            .comment("Scan grid step (1 = every block, 2 = every other block).",
                "Lower = more precise but slower. 2 is fine for finding trees/ores/chests.")
            .defineInRange("scanStep", 2, 1, 8);

        WORLD_SCAN_CACHE_TICKS = builder
            .comment("How many ticks a vision scan result is reused (20 ticks = 1 second)")
            .defineInRange("scanCacheTicks", 20, 5, 200);

        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");

        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);

        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);

        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);

        FORCE_LOAD_CHUNKS = builder
            .comment("Keep the chunk each Steve stands in force-loaded so Steves",
                "keep working on a dedicated server even when no player is online")
            .define("forceLoadChunks", true);

        builder.pop();

        SPEC = builder.build();
    }
}
