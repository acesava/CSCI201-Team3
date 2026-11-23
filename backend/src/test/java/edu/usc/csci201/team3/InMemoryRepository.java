package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Amount;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double mirroring JdbcRepository's semantics, including cascades and compare-and-set updates. */
class InMemoryRepository implements Repository {
    private record Session(UUID userId, Instant expiresAt) {}

    final Map<UUID, User> users = new LinkedHashMap<>();
    final Map<String, Session> sessions = new HashMap<>();
    final Map<UUID, Participant> participants = new LinkedHashMap<>();
    final Map<UUID, Expense> expenses = new LinkedHashMap<>();
    final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
    final Map<String, CachedSummary> summaries = new HashMap<>();
    final Map<String, Integer> quotas = new HashMap<>();

    public synchronized Optional<User> findUserByEmail(String email) {
        return users.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
    }

    public synchronized Optional<User> findUserById(UUID userId) {
        return Optional.ofNullable(users.get(userId));
    }

    public synchronized void createUser(User user, Participant self) {
        if (findUserByEmail(user.email()).isPresent()) throw ApiException.conflict("An account with this email already exists");
        users.put(user.id(), user);
        participants.put(self.id(), self);
    }

    public synchronized void updateProfile(UUID userId, String displayName, Preferences preferences) {
        User u = users.get(userId);
        users.put(userId, new User(u.id(), u.email(), displayName, u.passwordHash(), preferences, u.createdAt()));
        participants.replaceAll((id, p) -> p.ownerId().equals(userId) && p.isSelf()
                ? new Participant(p.id(), p.ownerId(), displayName, p.phone(), true, p.archived(), p.createdAt()) : p);
    }

    public synchronized void updatePassword(UUID userId, String passwordHash) {
        User u = users.get(userId);
        users.put(userId, new User(u.id(), u.email(), u.displayName(), passwordHash, u.preferences(), u.createdAt()));
    }

    public synchronized void deleteUser(UUID userId) {
        users.remove(userId);
        sessions.values().removeIf(s -> s.userId().equals(userId));
        participants.values().removeIf(p -> p.ownerId().equals(userId));
        expenses.values().removeIf(e -> e.ownerId().equals(userId));
        settlements.values().removeIf(s -> s.ownerId().equals(userId));
    }

    public synchronized void createSession(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
        sessions.put(tokenHash, new Session(userId, expiresAt));
    }

    public synchronized Optional<UUID> findSessionUser(String tokenHash, Instant now) {
        Session s = sessions.get(tokenHash);
        return s == null || !s.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(s.userId());
    }

    public synchronized void deleteSession(String tokenHash) {
        sessions.remove(tokenHash);
    }

    public synchronized void deleteSessionsForUser(UUID userId) {
        sessions.values().removeIf(s -> s.userId().equals(userId));
    }

    public synchronized List<Participant> listParticipants(UUID ownerId) {
        return participants.values().stream().filter(p -> p.ownerId().equals(ownerId))
                .sorted(Comparator.comparing((Participant p) -> !p.isSelf())).toList();
    }

    public synchronized void saveParticipant(Participant participant) {
        participants.put(participant.id(), participant);
    }

    public synchronized boolean participantHasRecords(UUID id) {
        return expenses.values().stream().anyMatch(e -> e.shares().stream().anyMatch(a -> a.participantId().equals(id))
                || e.payments().stream().anyMatch(a -> a.participantId().equals(id)))
                || settlements.values().stream().anyMatch(s -> s.fromParticipantId().equals(id) || s.toParticipantId().equals(id));
    }

    public synchronized void deleteParticipant(UUID participantId) {
        participants.remove(participantId);
    }

    public synchronized void saveExpense(Expense expense) {
        expenses.put(expense.id(), expense);
    }

    public synchronized Optional<Expense> findExpense(UUID expenseId) {
        return Optional.ofNullable(expenses.get(expenseId));
    }

    public synchronized List<Expense> listExpenses(UUID ownerId, LocalDate from, LocalDate toExclusive) {
        return expenses.values().stream()
                .filter(e -> e.ownerId().equals(ownerId) && !e.date().isBefore(from) && e.date().isBefore(toExclusive))
                .sorted(Comparator.comparing(Expense::date).reversed()).toList();
    }

    public synchronized void deleteExpense(UUID expenseId) {
        expenses.remove(expenseId);
    }

    public synchronized void saveSettlement(Settlement settlement) {
        settlements.put(settlement.id(), settlement);
    }

    public synchronized Optional<Settlement> findSettlement(UUID settlementId) {
        return Optional.ofNullable(settlements.get(settlementId));
    }

    public synchronized List<Settlement> listSettlements(UUID ownerId) {
        List<Settlement> result = new ArrayList<>(settlements.values().stream().filter(s -> s.ownerId().equals(ownerId)).toList());
        result.sort(Comparator.comparing(Settlement::createdAt).reversed());
        return result;
    }

    public synchronized boolean updateSettlementStatus(UUID id, String expectedStatus, String newStatus, Instant at) {
        Settlement s = settlements.get(id);
        if (s == null || !s.status().equals(expectedStatus)) return false;
        settlements.put(id, new Settlement(s.id(), s.ownerId(), s.fromParticipantId(), s.toParticipantId(), s.amountCents(),
                s.date(), s.method(), s.note(), newStatus, s.createdAt(), "confirmed".equals(newStatus) ? at : null));
        return true;
    }

    public synchronized void deleteSettlement(UUID settlementId) {
        settlements.remove(settlementId);
    }

    public synchronized List<ShareRow> findMonthlyShares(UUID userId, YearMonth month) {
        List<ShareRow> rows = new ArrayList<>();
        for (Expense e : expenses.values()) {
            if (!e.ownerId().equals(userId) || !YearMonth.from(e.date()).equals(month)) continue;
            for (Amount share : e.shares()) {
                Participant p = participants.get(share.participantId());
                if (p != null && p.isSelf()) rows.add(new ShareRow(e.date(), e.category(), share.amountCents()));
            }
        }
        return rows;
    }

    public synchronized Optional<CachedSummary> findSummary(UUID userId, YearMonth month) {
        return Optional.ofNullable(summaries.get(userId + "/" + month));
    }

    public synchronized void saveSummary(UUID userId, YearMonth month, String fingerprint, String summary, Instant at) {
        summaries.put(userId + "/" + month, new CachedSummary(fingerprint, summary, at));
    }

    public synchronized boolean consumeQuota(UUID userId, String operation, LocalDate day, int dailyLimit) {
        String key = userId + "/" + operation + "/" + day;
        int used = quotas.getOrDefault(key, 0);
        if (used >= dailyLimit) return false;
        quotas.put(key, used + 1);
        return true;
    }
}
