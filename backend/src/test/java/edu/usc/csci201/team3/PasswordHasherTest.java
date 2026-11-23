package edu.usc.csci201.team3;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test void verifiesOnlyTheOriginalPassword() {
        String stored = hasher.hash("correct horse battery");
        assertTrue(stored.startsWith("pbkdf2_sha256$1000$"));
        assertFalse(stored.contains("correct"));
        assertTrue(hasher.verify("correct horse battery", stored));
        assertFalse(hasher.verify("Correct horse battery", stored));
    }

    @Test void saltsMakeEqualPasswordsHashDifferently() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"));
    }

    @Test void malformedHashesNeverVerify() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", "plaintext"));
        assertFalse(hasher.verify("x", "pbkdf2_sha256$abc$!!$!!"));
    }
}
