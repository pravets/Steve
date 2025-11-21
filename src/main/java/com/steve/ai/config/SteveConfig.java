package com.steve.ai.config;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

public class SteveConfig {
    public static String AI_PROVIDER = "groq";
    public static String OPENAI_API_KEY = "";
    public static String OPENAI_MODEL = "gpt-4-turbo-preview";
    public static int MAX_TOKENS = 8000;
    public static double TEMPERATURE = 0.7;
    public static int ACTION_TICK_DELAY = 20;
    public static boolean ENABLE_CHAT_RESPONSES = true;
    public static int MAX_ACTIVE_STEVES = 10;

    public static void init(File configFile) {
        Configuration config = new Configuration(configFile);
        config.load();

        AI_PROVIDER = config.get("ai", "provider", AI_PROVIDER, "AI provider to use: 'groq', 'openai', or 'gemini'").getString();

        OPENAI_API_KEY = config.get("openai", "apiKey", OPENAI_API_KEY, "Your OpenAI or Gemini API key").getString();
        OPENAI_MODEL = config.get("openai", "model", OPENAI_MODEL, "OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)").getString();
        MAX_TOKENS = config.get("openai", "maxTokens", MAX_TOKENS, "Maximum tokens per API request").getInt();
        TEMPERATURE = config.get("openai", "temperature", TEMPERATURE, "Temperature for AI responses (0.0-2.0, lower is more deterministic)").getDouble();

        ACTION_TICK_DELAY = config.get("behavior", "actionTickDelay", ACTION_TICK_DELAY, "Ticks between action checks (20 ticks = 1 second)").getInt();
        ENABLE_CHAT_RESPONSES = config.get("behavior", "enableChatResponses", ENABLE_CHAT_RESPONSES, "Allow Steves to respond in chat").getBoolean();
        MAX_ACTIVE_STEVES = config.get("behavior", "maxActiveSteves", MAX_ACTIVE_STEVES, "Maximum number of Steves that can be active simultaneously").getInt();

        if (config.hasChanged()) {
            config.save();
        }
    }
}