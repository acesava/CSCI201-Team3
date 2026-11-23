package edu.usc.csci201.team3;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 with a per-password salt. Stored as pbkdf2_sha256$iterations$salt$hash. */
final class PasswordHasher {
    static final int PRODUCTION_ITERATIONS = 150_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final int iterations;
    private final String dummyHash;

    PasswordHasher(int iterations) {
        this.iterations = iterations;
        this.dummyHash = hash("timing-equalizer");
    }

    String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "pbkdf2_sha256$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    boolean verify(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) return false;
        try {
            int rounds = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, rounds));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Spends the same time as a real check so unknown emails are not revealed by response timing. */
    void burnTime(String password) {
        verify(password, dummyHash);
    }

    private static byte[] derive(String password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            spec.clearPassword();
        }
    }
}
