package edu.usc.csci201.team3;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HandlerTest {
    private Handler handler(IpRateLimiter.CounterStore store) {
        return new Handler(new IpRateLimiter(store,
                Clock.fixed(Instant.ofEpochSecond(103), ZoneOffset.UTC)));
    }
    private Handler allowed() { return handler((key, expiry, limit) -> true); }
    private Map<String, Object> event(String method, String path) {
        return Map.of("rawPath", path, "requestContext", Map.of("http",
                Map.of("method", method, "sourceIp", "192.0.2.10")));
    }
    @Test void healthWorksWithGatewayEvent() {
        Map<String, Object> response = allowed().handleRequest(event("GET", "/health"), null);
        assertEquals(200, response.get("statusCode"));
        assertTrue(response.get("body").toString().contains("\"status\":\"ok\""));
        assertEquals(false, response.get("isBase64Encoded"));
    }
    @Test void unknownRoutesDoNotPretendToSucceed() {
        assertEquals(404, allowed().handleRequest(event("POST", "/expenses"), null).get("statusCode"));
    }
    @Test void healthDoesNotAcceptWrites() {
        assertEquals(405, allowed().handleRequest(event("POST", "/health"), null).get("statusCode"));
    }
    @Test void missingSourceCannotBypassLimit() {
        assertEquals(400, allowed().handleRequest(Map.of(), null).get("statusCode"));
    }
    @Test void exhaustedIpGets429BeforeAnyRouteAndRetryAfter() {
        Map<String, Object> response = handler((key, expiry, limit) -> false)
                .handleRequest(event("POST", "/expenses"), null);
        assertEquals(429, response.get("statusCode"));
        assertEquals("7", ((Map<?, ?>) response.get("headers")).get("retry-after"));
    }
    @Test void counterFailureDoesNotRunHealthRoute() {
        assertEquals(503, handler((key, expiry, limit) -> { throw new IllegalStateException(); })
                .handleRequest(event("GET", "/health"), null).get("statusCode"));
    }
    @Test void forgedForwardingHeadersDoNotChangeCounterKey() {
        var keys = new java.util.ArrayList<String>();
        Handler handler = handler((key, expiry, limit) -> { keys.add(key); return true; });
        var request = new java.util.HashMap<>(event("GET", "/health"));
        handler.handleRequest(request, null);
        request.put("headers", Map.of("x-forwarded-for", "203.0.113.55", "cf-connecting-ip", "203.0.113.66"));
        handler.handleRequest(request, null);
        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).contains("192.0.2.10"));
    }
}
