package edu.usc.csci201.team3;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Lambda entry point: per-IP rate limit first, then /health or the authenticated API. */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    static {
        // Lambda has no display; image decoding for OCR must not try to open one.
        System.setProperty("java.awt.headless", "true");
    }

    private final IpRateLimiter limiter;
    private final Supplier<Api> api;

    public Handler() {
        this(DynamoIpCounter.production(), Api.production());
    }

    Handler(IpRateLimiter limiter) {
        this(limiter, Api.production());
    }

    Handler(IpRateLimiter limiter, Supplier<Api> api) {
        this.limiter = limiter;
        this.api = api;
    }

    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String path = String.valueOf(event.getOrDefault("rawPath", "/"));
        Object requestContext = event.get("requestContext");
        Object http = requestContext instanceof Map<?, ?> rc ? rc.get("http") : null;
        // Only API Gateway's sourceIp is trusted; forwarded headers are caller-controlled.
        String sourceIp = http instanceof Map<?, ?> info && info.get("sourceIp") instanceof String ip ? ip : null;
        IpRateLimiter.Decision decision = limiter.check(sourceIp);
        if (decision.status() != 200) {
            String error = switch (decision.status()) {
                case 429 -> "Too many requests. Try again shortly.";
                case 400 -> "Missing request source";
                default -> "Service temporarily unavailable";
            };
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("cache-control", "no-store");
            if (decision.retryAfter() > 0) headers.put("retry-after", Integer.toString(decision.retryAfter()));
            return Map.of("statusCode", decision.status(), "headers", headers,
                    "body", "{\"error\":\"" + error + "\"}", "isBase64Encoded", false);
        }
        String method = http instanceof Map<?, ?> info ? String.valueOf(info.get("method")) : "";
        if ("/health".equals(path)) {
            if (!"GET".equals(method)) {
                return response(405, "{\"error\":\"Method not allowed\"}");
            }
            return response(200, "{\"status\":\"ok\",\"service\":\"csci201-team3-java\"}");
        }
        return route(event);
    }

    private Map<String, Object> route(Map<String, Object> event) {
        try {
            Api.Response result = api.get().handle(Request.from(event));
            if (result.status() == 204) return response(204, "");
            return response(result.status(), Body.JSON.writeValueAsString(result.body()));
        } catch (ApiException expected) {
            return error(expected.status(), expected.getMessage());
        } catch (Database.UnavailableException down) {
            // Fail closed: never guess at data when the database cannot be reached.
            System.err.println("Database unavailable: " + down.getMessage());
            return error(503, "Service temporarily unavailable. Please try again shortly.");
        } catch (JsonProcessingException | RuntimeException unexpected) {
            System.err.println("Unhandled error: " + unexpected.getClass().getSimpleName());
            return error(500, "Something went wrong. Please try again.");
        }
    }

    private Map<String, Object> error(int status, String message) {
        try {
            return response(status, Body.JSON.writeValueAsString(Map.of("error", message)));
        } catch (JsonProcessingException impossible) {
            return response(status, "{\"error\":\"Request failed\"}");
        }
    }

    private Map<String, Object> response(int status, String body) {
        return Map.of("statusCode", status, "headers", Map.of("content-type", "application/json", "cache-control", "no-store"), "body", body, "isBase64Encoded", false);
    }
}
