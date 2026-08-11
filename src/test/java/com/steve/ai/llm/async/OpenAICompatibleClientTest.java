package com.steve.ai.llm.async;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the OpenAI-compatible request body construction:
 * json mode, model presence, system prompt, and parameter overrides.
 */
class OpenAICompatibleClientTest {

    private OpenAICompatibleClient client(String apiKey, String model, boolean jsonMode) {
        return new OpenAICompatibleClient("test", "http://localhost:9999/v1", apiKey, model,
            8000, 0.7, jsonMode, 60);
    }

    @Test
    void jsonModeAddsResponseFormat() {
        JsonObject body = JsonParser.parseString(
            client("", "test-model", true).buildRequestBody("do it", Map.of())).getAsJsonObject();

        assertTrue(body.has("response_format"), "jsonMode must add response_format");
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
    }

    @Test
    void jsonModeDisabledOmitsResponseFormat() {
        JsonObject body = JsonParser.parseString(
            client("", "test-model", false).buildRequestBody("do it", Map.of())).getAsJsonObject();

        assertFalse(body.has("response_format"));
    }

    @Test
    void modelIsOmittedWhenEmpty() {
        JsonObject body = JsonParser.parseString(
            client("", "", false).buildRequestBody("do it", Map.of())).getAsJsonObject();

        assertFalse(body.has("model"), "Empty model must not be sent (LM Studio case)");
    }

    @Test
    void systemPromptIsIncludedInMessages() {
        JsonObject body = JsonParser.parseString(
            client("", "m", false).buildRequestBody("user text",
                Map.of("systemPrompt", "sys text"))).getAsJsonObject();

        assertEquals("sys text", body.getAsJsonArray("messages").get(0).getAsJsonObject()
            .get("content").getAsString());
        assertEquals("user text", body.getAsJsonArray("messages").get(1).getAsJsonObject()
            .get("content").getAsString());
    }

    @Test
    void paramsOverrideDefaults() {
        JsonObject body = JsonParser.parseString(
            client("", "default-model", false).buildRequestBody("do it",
                Map.of("model", "override-model", "maxTokens", 1234, "temperature", 0.1)))
            .getAsJsonObject();

        assertEquals("override-model", body.get("model").getAsString());
        assertEquals(1234, body.get("max_tokens").getAsInt());
        assertEquals(0.1, body.get("temperature").getAsDouble());
    }

    @Test
    void hasApiKeyReflectsConfig() {
        assertFalse(client("", "m", false).hasApiKey());
        assertTrue(client("sk-test", "m", false).hasApiKey());
    }
}
