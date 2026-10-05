package edu.usc.csci201.team3;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HandlerTest {
    private Map<String, Object> event(String method, String path) {
        return Map.of("rawPath", path, "requestContext", Map.of("http", Map.of("method", method)));
    }
    @Test void healthWorksWithFunctionUrlEvent() {
        Map<String, Object> response = new Handler().handleRequest(event("GET", "/health"), null);
        assertEquals(200, response.get("statusCode"));
        assertTrue(response.get("body").toString().contains("\"status\":\"ok\""));
        assertEquals(false, response.get("isBase64Encoded"));
    }
    @Test void unknownRoutesDoNotPretendToSucceed() {
        assertEquals(404, new Handler().handleRequest(event("POST", "/expenses"), null).get("statusCode"));
    }
    @Test void healthDoesNotAcceptWrites() {
        assertEquals(405, new Handler().handleRequest(event("POST", "/health"), null).get("statusCode"));
    }
    @Test void malformedEventReturnsAnHttpResponse() {
        assertEquals(404, new Handler().handleRequest(Map.of(), null).get("statusCode"));
    }
}
