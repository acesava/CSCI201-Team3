package edu.usc.csci201.team3;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

/** Starter HTTP handler. Business routes and authentication are not implemented yet. */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String path = String.valueOf(event.getOrDefault("rawPath", "/"));
        Object requestContext = event.get("requestContext");
        Object http = requestContext instanceof Map<?, ?> rc ? rc.get("http") : null;
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
