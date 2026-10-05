package edu.usc.csci201.team3;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

/** Starter HTTP handler. Business routes and authentication are not implemented yet. */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final IpRateLimiter limiter;

    public Handler() {
        this(DynamoIpCounter.production());
    }

    Handler(IpRateLimiter limiter) {
        this.limiter = limiter;
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
            Map<String, String> headers = new java.util.HashMap<>();
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
        return response(404, "{\"error\":\"Route not implemented\"}");
    }

    private Map<String, Object> response(int status, String body) {
        return Map.of("statusCode", status, "headers", Map.of("content-type", "application/json", "cache-control", "no-store"), "body", body, "isBase64Encoded", false);
    }
}
