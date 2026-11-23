package edu.usc.csci201.team3;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The parts of an API Gateway HTTP API (payload v2) event that routes need. */
record Request(String method, String path, Map<String, String> headers,
               Map<String, String> query, String body) {
    static final int MAX_BODY_BYTES = 6 * 1024 * 1024;

    static Request from(Map<String, Object> event) {
        String path = String.valueOf(event.getOrDefault("rawPath", "/"));
        Object requestContext = event.get("requestContext");
        Object http = requestContext instanceof Map<?, ?> rc ? rc.get("http") : null;
        String method = http instanceof Map<?, ?> info && info.get("method") instanceof String m ? m : "";
        return new Request(method.toUpperCase(Locale.ROOT), path,
                lowerCaseKeys(event.get("headers")), lowerCaseKeys(event.get("queryStringParameters")),
                decodeBody(event));
    }

    String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    String queryParam(String name) {
        return query.get(name);
    }

    /** The bearer token, or null. */
    String bearerToken() {
        String value = header("authorization");
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() || token.length() > 200 ? null : token;
    }

    /** Matches a pattern such as "/groups/{}/expenses" and returns the {} segments, or null. */
    List<String> match(String expectedMethod, String pattern) {
        if (!expectedMethod.equals(method)) return null;
        String[] want = pattern.substring(1).split("/");
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String[] have = trimmed.substring(1).split("/");
        if (want.length != have.length) return null;
        List<String> params = new java.util.ArrayList<>();
        for (int i = 0; i < want.length; i++) {
            if ("{}".equals(want[i])) {
                if (have[i].isEmpty()) return null;
                params.add(have[i]);
            } else if (!want[i].equals(have[i])) {
                return null;
            }
        }
        return params;
    }

    private static Map<String, String> lowerCaseKeys(Object raw) {
        Map<String, String> result = new HashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    result.put(key.toLowerCase(Locale.ROOT), value);
                }
            }
        }
        return result;
    }

    private static String decodeBody(Map<String, Object> event) {
        if (!(event.get("body") instanceof String body)) return null;
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            try {
                body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                throw ApiException.badRequest("Request body could not be decoded");
            }
        }
        if (body.length() > MAX_BODY_BYTES) throw new ApiException(413, "Request body is too large");
        return body;
    }
}
