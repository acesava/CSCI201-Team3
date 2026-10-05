package edu.usc.csci201.team3;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IpRateLimiterTest {
    @Test void allowanceIsSharedAcrossInstancesButSeparateAcrossIpsAndWindows() {
        var counts = new HashMap<String, Integer>();
        IpRateLimiter.CounterStore store = (key, expiry, limit) -> {
            int count = counts.getOrDefault(key, 0);
            if (count >= limit) return false;
            counts.put(key, count + 1);
            return true;
        };
        Clock time = Clock.fixed(Instant.ofEpochSecond(103), ZoneOffset.UTC);
        var first = new IpRateLimiter(store, time);
        var second = new IpRateLimiter(store, time);
        for (int i = 0; i < 30; i++) {
            assertEquals(200, (i % 2 == 0 ? first : second).check("192.0.2.10").status());
        }
        assertEquals(429, second.check("192.0.2.10").status());
        assertEquals(200, second.check("192.0.2.11").status());
        var nextWindow = new IpRateLimiter(store,
                Clock.fixed(Instant.ofEpochSecond(110), ZoneOffset.UTC));
        assertEquals(200, nextWindow.check("192.0.2.10").status());
    }
    @Test void missingIpNeverCallsStorage() {
        var limiter = new IpRateLimiter((key, expiry, limit) -> { fail("Unexpected counter call"); return true; }, Clock.systemUTC());
        assertEquals(400, limiter.check(null).status());
        assertEquals(400, limiter.check(" ").status());
    }
}
