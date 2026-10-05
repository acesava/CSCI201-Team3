package edu.usc.csci201.team3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/** Shared fixed-window limit. Each public IP gets 30 requests per 10-second window. */
final class IpRateLimiter {
    static final int LIMIT = 30;
    static final int WINDOW_SECONDS = 10;
    interface CounterStore {
        boolean acquire(String key, long expiresAt, int limit);
    }
    record Decision(int status, int retryAfter) {}
    private final CounterStore store;
    private final Clock clock;

    IpRateLimiter(CounterStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    Decision check(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank() || sourceIp.length() > 128) {
            return new Decision(400, 0);
        }
        long now = clock.instant().getEpochSecond();
        long window = Math.floorDiv(now, WINDOW_SECONDS);
        long end = (window + 1) * WINDOW_SECONDS;
        String key = "ip#" + digest(sourceIp) + "#" + window;
        try {
            // TTL cleanup is asynchronous; the window in the key controls expiration.
            return store.acquire(key, end + 120, LIMIT)
                    ? new Decision(200, 0) : new Decision(429, (int) (end - now));
        } catch (RuntimeException unavailable) {
            // Record only the exception type, never an IP, key, or SDK error payload.
            System.err.println("IP counter unavailable: " + unavailable.getClass().getSimpleName());
            // Never run business routes when the shared counter cannot be checked.
            return new Decision(503, WINDOW_SECONDS);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
