package edu.usc.csci201.team3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Writes a short natural-language summary from a prompt. */
interface SummaryWriter {
    String write(String prompt) throws IOException, InterruptedException;

    /** Gemini when GEMINI_API_KEY is set; otherwise every call fails and the app shows "unavailable". */
    static SummaryWriter fromEnvironment() {
        String key = System.getenv("GEMINI_API_KEY");
        String model = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");
        if (key == null || key.isBlank()) {
            return prompt -> {
                throw new IOException("GEMINI_API_KEY is not configured");
            };
        }
        return new GeminiClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), key, model);
    }

    /** Gemini generateContent REST API. The key stays in Lambda configuration and is sent as a header. */
    final class GeminiClient implements SummaryWriter {
        private final HttpClient http;
        private final String apiKey;
        private final String model;

        GeminiClient(HttpClient http, String apiKey, String model) {
            this.http = http;
            this.apiKey = apiKey;
            this.model = model;
        }

        public String write(String prompt) throws IOException, InterruptedException {
            ObjectNode request = Body.JSON.createObjectNode();
            ArrayNode contents = request.putArray("contents");
            contents.addObject().putArray("parts").addObject().put("text", prompt);
            request.putObject("generationConfig").put("temperature", 0.3).put("maxOutputTokens", 400);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                            + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent"))
                    .timeout(Duration.ofSeconds(12))
                    .header("content-type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(Body.JSON.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Gemini returned HTTP " + response.statusCode());
            return extractText(response.body());
        }

        static String extractText(String json) throws IOException {
            JsonNode parts = Body.JSON.readTree(json).path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) text.append(part.path("text").asText(""));
            String result = text.toString().trim();
            if (result.isEmpty()) throw new IOException("Gemini returned no text");
            return result.length() > 1200 ? result.substring(0, 1200) : result;
        }
    }
}
