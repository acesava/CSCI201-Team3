package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.CachedSummary;
import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.Preferences;
import edu.usc.csci201.team3.Models.Settlement;
import edu.usc.csci201.team3.Models.ShareRow;
import edu.usc.csci201.team3.Models.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence used by the services. Each method is atomic on its own.
 * Implementations must be safe to call from several threads at once.
 */
interface Repository {
    // Users and sessions
    Optional<User> findUserByEmail(String email);
    Optional<User> findUserById(UUID userId);
    /** Creates the user and their self participant together; fails with 409 when the email is taken. */
    void createUser(User user, Participant self);
    void updateProfile(UUID userId, String displayName, Preferences preferences);
    void updatePassword(UUID userId, String passwordHash);
    /** Deletes the account and everything it owns. */
    void deleteUser(UUID userId);
    void createSession(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt);
    Optional<UUID> findSessionUser(String tokenHash, Instant now);
    void deleteSession(String tokenHash);
    void deleteSessionsForUser(UUID userId);

    // Participants
    /** All of the owner's participants, including archived ones, self first. */
    List<Participant> listParticipants(UUID ownerId);
    void saveParticipant(Participant participant);
    boolean participantHasRecords(UUID participantId);
    void deleteParticipant(UUID participantId);

    // Expenses
    /** Inserts or fully replaces an expense with its items, shares, and payments in one transaction. */
    void saveExpense(Expense expense);
    Optional<Expense> findExpense(UUID expenseId);
    /** The owner's expenses dated in [from, toExclusive), newest first. */
    List<Expense> listExpenses(UUID ownerId, LocalDate from, LocalDate toExclusive);
    void deleteExpense(UUID expenseId);

    // Settlements
    void saveSettlement(Settlement settlement);
    Optional<Settlement> findSettlement(UUID settlementId);
    List<Settlement> listSettlements(UUID ownerId);
    /** Compare-and-set so two confirmations cannot both apply. */
    boolean updateSettlementStatus(UUID settlementId, String expectedStatus, String newStatus, Instant at);
    void deleteSettlement(UUID settlementId);

    // Monthly summaries and quotas
    List<ShareRow> findMonthlyShares(UUID userId, YearMonth month);
    Optional<CachedSummary> findSummary(UUID userId, YearMonth month);
    void saveSummary(UUID userId, YearMonth month, String fingerprint, String summary, Instant at);
    /** Atomically uses one unit of today's allowance; false when the limit is reached. */
    boolean consumeQuota(UUID userId, String operation, LocalDate day, int dailyLimit);
}
