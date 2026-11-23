package edu.usc.csci201.team3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validating accessors over a JSON request body. Every failure is a user-facing 400. */
final class Body {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long MAX_CENTS = 100_000_000L;

    private final JsonNode node;

    private Body(JsonNode node) {
        this.node = node;
    }

    static Body parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Body(JSON.createObjectNode());
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) throw ApiException.badRequest("Request body must be a JSON object");
            return new Body(node);
        } catch (JsonProcessingException invalid) {
            throw ApiException.badRequest("Request body is not valid JSON");
        }
    }

    static Body of(JsonNode node) {
        if (node == null || !node.isObject()) throw ApiException.badRequest("Expected a JSON object");
        return new Body(node);
    }

    boolean has(String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    /** True when the key appears at all, even with a null value. */
    boolean present(String field) {
        return node.has(field);
    }

    private JsonNode get(String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? MissingNode.getInstance() : value;
    }

    String text(String field, int maxLength) {
        String value = optionalText(field, maxLength);
        if (value == null) throw ApiException.badRequest(field + " is required");
        return value;
    }

    String optionalText(String field, int maxLength) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return null;
        if (!value.isTextual()) throw ApiException.badRequest(field + " must be text");
        String trimmed = value.asText().trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw ApiException.badRequest(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    /** Passwords are not trimmed. */
    String secret(String field) {
        JsonNode value = get(field);
        if (!value.isTextual() || value.asText().isEmpty()) throw ApiException.badRequest(field + " is required");
        return value.asText();
    }

    long cents(String field) {
        if (!has(field)) throw ApiException.badRequest(field + " is required");
        return optionalCents(field, 0);
    }

    long optionalCents(String field, long fallback) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiException.badRequest(field + " must be a whole number of cents");
        }
        long cents = value.asLong();
        if (cents < 0 || cents > MAX_CENTS) throw ApiException.badRequest(field + " is out of range");
        return cents;
    }

    int optionalInt(String field, int fallback, int min, int max) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw ApiException.badRequest(field + " must be a whole number");
        int number = value.asInt();
        if (number < min || number > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return number;
    }

    Boolean optionalBoolean(String field) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return null;
        if (!value.isBoolean()) throw ApiException.badRequest(field + " must be true or false");
        return value.asBoolean();
    }

    UUID uuid(String field) {
        UUID value = optionalUuid(field);
        if (value == null) throw ApiException.badRequest(field + " is required");
        return value;
    }

    UUID optionalUuid(String field) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return null;
        return toUuid(value, field);
    }

    LocalDate date(String field) {
        JsonNode value = get(field);
        if (!value.isTextual()) throw ApiException.badRequest(field + " is required (YYYY-MM-DD)");
        try {
            return LocalDate.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            throw ApiException.badRequest(field + " must be a date in YYYY-MM-DD format");
        }
    }

    List<UUID> uuidList(String field) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return List.of();
        if (!value.isArray()) throw ApiException.badRequest(field + " must be a list");
        List<UUID> result = new ArrayList<>();
        for (JsonNode element : value) result.add(toUuid(element, field));
        return result;
    }

    List<Body> objectList(String field, int maxSize) {
        JsonNode value = get(field);
        if (value.isMissingNode()) return List.of();
        if (!value.isArray()) throw ApiException.badRequest(field + " must be a list");
        if (value.size() > maxSize) throw ApiException.badRequest(field + " has too many entries");
        List<Body> result = new ArrayList<>();
        for (JsonNode element : value) result.add(Body.of(element));
        return result;
    }

    Body object(String field) {
        JsonNode value = get(field);
        return value.isMissingNode() ? new Body(JSON.createObjectNode()) : Body.of(value);
    }

    private static UUID toUuid(JsonNode value, String field) {
        try {
            if (value.isTextual()) return UUID.fromString(value.asText());
        } catch (IllegalArgumentException ignored) {
            // Falls through to the shared error.
        }
        throw ApiException.badRequest(field + " must be a valid id");
    }
}
