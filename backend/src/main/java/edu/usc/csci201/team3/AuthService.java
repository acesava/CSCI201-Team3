package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.Preferences;
import edu.usc.csci201.team3.Models.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Registration, login, opaque bearer sessions, and profile management. */
final class AuthService {
    static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "CAD", "CNY", "JPY", "KRW", "INR", "MXN");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Repository repo;
    private final PasswordHasher hasher;
    private final Clock clock;

    AuthService(Repository repo, PasswordHasher hasher, Clock clock) {
        this.repo = repo;
        this.hasher = hasher;
        this.clock = clock;
    }

    Map<String, Object> register(Body body) {
        String email = normalizeEmail(body.text("email", 254));
        String displayName = body.text("displayName", 60);
        String password = body.secret("password");
        validatePassword(password);
        Instant now = clock.instant();
        User user = new User(UUID.randomUUID(), email, displayName, hasher.hash(password), Preferences.defaults(), now);
        repo.createUser(user, new Participant(UUID.randomUUID(), user.id(), displayName, null, true, false, now));
        return startSession(user);
    }

    Map<String, Object> login(Body body) {
        String email = normalizeEmail(body.text("email", 254));
        String password = body.secret("password");
        var user = repo.findUserByEmail(email);
        if (user.isEmpty()) {
            hasher.burnTime(password);
            throw new ApiException(401, "Incorrect email or password");
        }
        if (!hasher.verify(password, user.get().passwordHash())) {
            throw new ApiException(401, "Incorrect email or password");
        }
        return startSession(user.get());
    }

    void logout(String token) {
        if (token != null) repo.deleteSession(hashToken(token));
    }

    /** Resolves the bearer token to its user or fails with 401. */
    User authenticate(String token) {
        if (token == null) throw ApiException.unauthorized();
        UUID userId = repo.findSessionUser(hashToken(token), clock.instant()).orElseThrow(ApiException::unauthorized);
        return repo.findUserById(userId).orElseThrow(ApiException::unauthorized);
    }

    Map<String, Object> updateProfile(User user, Body body) {
        String displayName = body.has("displayName") ? body.text("displayName", 60) : user.displayName();
        Body prefs = body.object("preferences");
        Preferences current = user.preferences();
        Boolean notify = prefs.optionalBoolean("expenseNotifications");
        Boolean summaryEmail = prefs.optionalBoolean("monthlySummaryEmail");
        String currency = prefs.optionalText("currency", 3);
        if (currency != null && !CURRENCIES.contains(currency.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("Unsupported currency");
        }
        Preferences updated = new Preferences(
                notify == null ? current.expenseNotifications() : notify,
                summaryEmail == null ? current.monthlySummaryEmail() : summaryEmail,
                currency == null ? current.currency() : currency.toUpperCase(Locale.ROOT));
        repo.updateProfile(user.id(), displayName, updated);
        return Views.user(new User(user.id(), user.email(), displayName, user.passwordHash(), updated, user.createdAt()));
    }

    /** Changing the password signs out every other session, then issues a fresh one. */
    Map<String, Object> changePassword(User user, Body body) {
        requirePassword(user, body.secret("currentPassword"));
        String next = body.secret("newPassword");
        validatePassword(next);
        repo.updatePassword(user.id(), hasher.hash(next));
        repo.deleteSessionsForUser(user.id());
        return startSession(user);
    }

    void deleteAccount(User user, Body body) {
        requirePassword(user, body.secret("password"));
        repo.deleteUser(user.id());
    }

    private void requirePassword(User user, String password) {
        if (!hasher.verify(password, user.passwordHash())) throw ApiException.forbidden("Your current password is incorrect");
    }

    private Map<String, Object> startSession(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Instant expires = now.plus(SESSION_LIFETIME);
        repo.createSession(hashToken(token), user.id(), now, expires);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("expiresAt", expires.toString());
        result.put("user", Views.user(user));
        return result;
    }

    private static String normalizeEmail(String email) {
        String normalized = email.toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches()) throw ApiException.badRequest("Enter a valid email address");
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password.length() < 8) throw ApiException.badRequest("Password must be at least 8 characters");
        if (password.length() > 128) throw ApiException.badRequest("Password must be at most 128 characters");
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
