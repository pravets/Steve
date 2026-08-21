package com.steve.ai.voice;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI-compatible STT client (any provider):
 * POST {baseUrl}/audio/transcriptions with a multipart body
 * (file=audio.wav, model=..., language=...). Responses in either
 * {"text": "..."} JSON or plain text are both accepted.
 */
public final class MultipartSttClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private MultipartSttClient() {}

    public static CompletableFuture<String> transcribe(byte[] wav) {
        String baseUrl = SteveConfig.STT_BASE_URL.get().replaceAll("/+$", "");
        String model = SteveConfig.STT_MODEL.get();
        String language = SteveConfig.STT_LANGUAGE.get();
        String apiKey = SteveConfig.STT_API_KEY.get();

        String boundary = "steve" + UUID.randomUUID();
        byte[] body = buildMultipart(wav, model, language, boundary);

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/audio/transcriptions"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(resp -> {
                if (resp.statusCode() >= 300) {
                    // Log status only - the body may contain transcribed speech
                    SteveMod.LOGGER.warn("STT error status {} for player request", resp.statusCode());
                    throw new RuntimeException("STT endpoint returned " + resp.statusCode());
                }
                return parseText(resp.body());
            });
    }

    /** Extracts the transcription from {"text": "..."} JSON or plain text. */
    public static String parseText(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                var root = com.google.gson.JsonParser.parseString(trimmed).getAsJsonObject();
                if (root.has("text") && !root.get("text").isJsonNull()) {
                    return root.get("text").getAsString();
                }
            } catch (Exception ignored) {
                // fall through to plain-text handling
            }
            return "";
        }
        return trimmed;
    }

    private static byte[] buildMultipart(byte[] wav, String model, String language, String boundary) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);

            writePart(out, boundary, "file", "voice.wav", "audio/wav", wav, crlf);
            writeField(out, boundary, "model", model, crlf);
            if (language != null && !language.isBlank()) {
                writeField(out, boundary, "language", language, crlf);
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name,
                                  String filename, String contentType, byte[] data, byte[] crlf) throws java.io.IOException {
        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(data, 0, data.length);
        out.write(crlf);
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name,
                                   String value, byte[] crlf) throws java.io.IOException {
        String part = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + value + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }
}
