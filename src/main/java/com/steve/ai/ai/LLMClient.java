package com.steve.ai.ai;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;

public class LLMClient {
    public String requestLLM(String prompt) {
        // Реализация для Groq/OpenAI через HTTP API
        String apiKey = SteveConfig.OPENAI_API_KEY;
        String model = SteveConfig.OPENAI_MODEL;
        String apiUrl = "https://api.openai.com/v1/chat/completions";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(apiUrl);
            request.addHeader("Authorization", "Bearer " + apiKey);
            request.addHeader("Content-Type", "application/json");
            String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
            request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                String text = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                SteveMod.LOGGER.info("LLM response: {}", text);
                return text;
            }
        } catch (Exception e) {
            SteveMod.LOGGER.error("LLM request failed", e);
            return "[ERROR]";
        }
    }
}
