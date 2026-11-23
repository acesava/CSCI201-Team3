package edu.usc.csci201.team3;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Persistent records. All money is integer cents. */
final class Models {
    private Models() {}

    record Preferences(boolean expenseNotifications, boolean monthlySummaryEmail, String currency) {
        static Preferences defaults() {
            return new Preferences(true, false, "USD");
        }
    }

    record User(UUID id, String email, String displayName, String passwordHash,
                Preferences preferences, Instant createdAt) {}

    /**
     * Someone the account holder splits with. They never log in; a phone number lets the app text them.
     * Each owner has exactly one self participant representing themselves.
     */
    record Participant(UUID id, UUID ownerId, String displayName, String phone, boolean isSelf,
                       boolean archived, Instant createdAt) {}

    record Amount(UUID participantId, long amountCents) {}

    record Item(UUID id, String name, int quantity, long totalCents, List<Amount> allocations) {}

    record Expense(UUID id, UUID ownerId, String description, String category, LocalDate date,
                   long amountCents, long taxCents, long tipCents, String entryMethod, String splitMethod,
                   String notes, Instant createdAt, List<Item> items, List<Amount> shares, List<Amount> payments) {}

    record Settlement(UUID id, UUID ownerId, UUID fromParticipantId, UUID toParticipantId, long amountCents,
                      LocalDate date, String method, String note, String status,
                      Instant createdAt, Instant confirmedAt) {}

    /** One of the account holder's own assigned shares, used for monthly statistics. */
    record ShareRow(LocalDate date, String category, long amountCents) {}

    record CachedSummary(String fingerprint, String summary, Instant createdAt) {}
}
