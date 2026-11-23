package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Amount;
import edu.usc.csci201.team3.Models.CachedSummary;
import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Item;
import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.Preferences;
import edu.usc.csci201.team3.Models.Settlement;
import edu.usc.csci201.team3.Models.ShareRow;
import edu.usc.csci201.team3.Models.User;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Supabase Postgres implementation. Table definitions live in database/migrations. */
final class JdbcRepository implements Repository {
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private final Database db;

    JdbcRepository(Database db) {
        this.db = db;
    }

    // ---- Users and sessions ----

    private static final String USER_COLUMNS =
            "user_id, email, display_name, password_hash, expense_notifications, monthly_summary_email, currency, created_at";

    public Optional<User> findUserByEmail(String email) {
        return db.read(c -> first(c, "select " + USER_COLUMNS + " from public.users where lower(email) = lower(?)",
                s -> s.setString(1, email), JdbcRepository::user));
    }

    public Optional<User> findUserById(UUID userId) {
        return db.read(c -> first(c, "select " + USER_COLUMNS + " from public.users where user_id = ?",
                s -> s.setObject(1, userId), JdbcRepository::user));
    }

    public void createUser(User user, Participant self) {
        db.transaction(c -> {
            try {
                update(c, "insert into public.users (" + USER_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?)", s -> {
                    s.setObject(1, user.id());
                    s.setString(2, user.email());
                    s.setString(3, user.displayName());
                    s.setString(4, user.passwordHash());
                    s.setBoolean(5, user.preferences().expenseNotifications());
                    s.setBoolean(6, user.preferences().monthlySummaryEmail());
                    s.setString(7, user.preferences().currency());
                    s.setTimestamp(8, Timestamp.from(user.createdAt()));
                });
            } catch (SQLException failure) {
                if ("23505".equals(failure.getSQLState())) {
                    throw ApiException.conflict("An account with this email already exists");
                }
                throw failure;
            }
            upsertParticipant(c, self);
            return null;
        });
    }

    public void updateProfile(UUID userId, String displayName, Preferences preferences) {
        db.transaction(c -> {
            update(c, "update public.users set display_name = ?, expense_notifications = ?, monthly_summary_email = ?, currency = ? where user_id = ?", s -> {
                s.setString(1, displayName);
                s.setBoolean(2, preferences.expenseNotifications());
                s.setBoolean(3, preferences.monthlySummaryEmail());
                s.setString(4, preferences.currency());
                s.setObject(5, userId);
            });
            // The self participant's name is what texts to other participants show as the requester.
            update(c, "update public.participants set display_name = ? where owner_id = ? and is_self", s -> {
                s.setString(1, displayName);
                s.setObject(2, userId);
            });
            return null;
        });
    }

    public void updatePassword(UUID userId, String passwordHash) {
        db.read(c -> update(c, "update public.users set password_hash = ? where user_id = ?", s -> {
            s.setString(1, passwordHash);
            s.setObject(2, userId);
        }));
    }

    public void deleteUser(UUID userId) {
        // Every owned table cascades from users.
        db.read(c -> update(c, "delete from public.users where user_id = ?", s -> s.setObject(1, userId)));
    }

    public void createSession(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
        db.transaction(c -> {
            update(c, "delete from public.sessions where user_id = ? and expires_at <= ?", s -> {
                s.setObject(1, userId);
                s.setTimestamp(2, Timestamp.from(createdAt));
            });
            update(c, "insert into public.sessions (token_hash, user_id, created_at, expires_at) values (?, ?, ?, ?)", s -> {
                s.setString(1, tokenHash);
                s.setObject(2, userId);
                s.setTimestamp(3, Timestamp.from(createdAt));
                s.setTimestamp(4, Timestamp.from(expiresAt));
            });
            return null;
        });
    }

    public Optional<UUID> findSessionUser(String tokenHash, Instant now) {
        return db.read(c -> first(c, "select user_id from public.sessions where token_hash = ? and expires_at > ?", s -> {
            s.setString(1, tokenHash);
            s.setTimestamp(2, Timestamp.from(now));
        }, r -> r.getObject(1, UUID.class)));
    }

    public void deleteSession(String tokenHash) {
        db.read(c -> update(c, "delete from public.sessions where token_hash = ?", s -> s.setString(1, tokenHash)));
    }

    public void deleteSessionsForUser(UUID userId) {
        db.read(c -> update(c, "delete from public.sessions where user_id = ?", s -> s.setObject(1, userId)));
    }

    // ---- Participants ----

    private static final String PARTICIPANT_COLUMNS = "participant_id, owner_id, display_name, phone_e164, is_self, archived, created_at";

    public List<Participant> listParticipants(UUID ownerId) {
        return db.read(c -> list(c, "select " + PARTICIPANT_COLUMNS + " from public.participants where owner_id = ?"
                + " order by is_self desc, created_at, participant_id", s -> s.setObject(1, ownerId), JdbcRepository::participant));
    }

    public void saveParticipant(Participant participant) {
        db.read(c -> upsertParticipant(c, participant));
    }

    public boolean participantHasRecords(UUID participantId) {
        return db.read(c -> first(c, """
                select exists (select 1 from public.expense_shares where participant_id = ?)
                    or exists (select 1 from public.expense_payments where participant_id = ?)
                    or exists (select 1 from public.item_allocations where participant_id = ?)
                    or exists (select 1 from public.settlements where from_participant_id = ? or to_participant_id = ?)
                """, s -> {
            for (int i = 1; i <= 5; i++) s.setObject(i, participantId);
        }, r -> r.getBoolean(1)).orElse(false));
    }

    public void deleteParticipant(UUID participantId) {
        db.read(c -> update(c, "delete from public.participants where participant_id = ?", s -> s.setObject(1, participantId)));
    }

    private static int upsertParticipant(Connection c, Participant p) throws SQLException {
        return update(c, "insert into public.participants (" + PARTICIPANT_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?)"
                + " on conflict (participant_id) do update set display_name = excluded.display_name,"
                + " phone_e164 = excluded.phone_e164, archived = excluded.archived", s -> {
            s.setObject(1, p.id());
            s.setObject(2, p.ownerId());
            s.setString(3, p.displayName());
            s.setString(4, p.phone());
            s.setBoolean(5, p.isSelf());
            s.setBoolean(6, p.archived());
            s.setTimestamp(7, Timestamp.from(p.createdAt()));
        });
    }

    // ---- Expenses ----

    private static final String EXPENSE_COLUMNS = "expense_id, owner_id, description, category, expense_date,"
            + " amount_cents, tax_cents, tip_cents, entry_method, split_method, notes, created_at";

    public void saveExpense(Expense e) {
        db.transaction(c -> {
            update(c, "insert into public.expenses (" + EXPENSE_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + """
                     on conflict (expense_id) do update set description = excluded.description, category = excluded.category,
                        expense_date = excluded.expense_date, amount_cents = excluded.amount_cents, tax_cents = excluded.tax_cents,
                        tip_cents = excluded.tip_cents, entry_method = excluded.entry_method,
                        split_method = excluded.split_method, notes = excluded.notes
                    """, s -> {
                s.setObject(1, e.id());
                s.setObject(2, e.ownerId());
                s.setString(3, e.description());
                s.setString(4, e.category());
                s.setDate(5, Date.valueOf(e.date()));
                s.setLong(6, e.amountCents());
                s.setLong(7, e.taxCents());
                s.setLong(8, e.tipCents());
                s.setString(9, e.entryMethod());
                s.setString(10, e.splitMethod());
                s.setString(11, e.notes());
                s.setTimestamp(12, Timestamp.from(e.createdAt()));
            });
            Binder byExpense = s -> s.setObject(1, e.id());
            update(c, "delete from public.expense_items where expense_id = ?", byExpense);
            update(c, "delete from public.expense_shares where expense_id = ?", byExpense);
            update(c, "delete from public.expense_payments where expense_id = ?", byExpense);
            try (PreparedStatement items = c.prepareStatement(
                    "insert into public.expense_items (item_id, expense_id, position, name, quantity, total_cents) values (?, ?, ?, ?, ?, ?)");
                 PreparedStatement allocations = c.prepareStatement(
                    "insert into public.item_allocations (item_id, participant_id, amount_cents) values (?, ?, ?)")) {
                int position = 0;
                for (Item item : e.items()) {
                    items.setObject(1, item.id());
                    items.setObject(2, e.id());
                    items.setInt(3, position++);
                    items.setString(4, item.name());
                    items.setInt(5, item.quantity());
                    items.setLong(6, item.totalCents());
                    items.addBatch();
                    for (Amount allocation : item.allocations()) {
                        allocations.setObject(1, item.id());
                        allocations.setObject(2, allocation.participantId());
                        allocations.setLong(3, allocation.amountCents());
                        allocations.addBatch();
                    }
                }
                items.executeBatch();
                allocations.executeBatch();
            }
            insertAmounts(c, "expense_shares", e.id(), e.shares());
            insertAmounts(c, "expense_payments", e.id(), e.payments());
            return null;
        });
    }

    public Optional<Expense> findExpense(UUID expenseId) {
        return db.read(c -> loadExpenses(c, "select " + EXPENSE_COLUMNS + " from public.expenses where expense_id = ?",
                s -> s.setObject(1, expenseId)).stream().findFirst());
    }

    public List<Expense> listExpenses(UUID ownerId, LocalDate from, LocalDate toExclusive) {
        return db.read(c -> loadExpenses(c, "select " + EXPENSE_COLUMNS + " from public.expenses"
                + " where owner_id = ? and expense_date >= ? and expense_date < ? order by expense_date desc, created_at desc", s -> {
            s.setObject(1, ownerId);
            s.setDate(2, Date.valueOf(from));
            s.setDate(3, Date.valueOf(toExclusive));
        }));
    }

    public void deleteExpense(UUID expenseId) {
        db.read(c -> update(c, "delete from public.expenses where expense_id = ?", s -> s.setObject(1, expenseId)));
    }

    private static void insertAmounts(Connection c, String table, UUID expenseId, List<Amount> amounts) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "insert into public." + table + " (expense_id, participant_id, amount_cents) values (?, ?, ?)")) {
            for (Amount amount : amounts) {
                statement.setObject(1, expenseId);
                statement.setObject(2, amount.participantId());
                statement.setLong(3, amount.amountCents());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Loads expense rows, then their children with one query per child table. */
    private static List<Expense> loadExpenses(Connection c, String sql, Binder binder) throws SQLException {
        List<Expense> headers = list(c, sql, binder, r -> new Expense(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                r.getString(3), r.getString(4), r.getDate(5).toLocalDate(), r.getLong(6), r.getLong(7), r.getLong(8),
                r.getString(9), r.getString(10), r.getString(11), r.getTimestamp(12).toInstant(), List.of(), List.of(), List.of()));
        if (headers.isEmpty()) return List.of();
        java.sql.Array idArray = c.createArrayOf("uuid", headers.stream().map(Expense::id).toArray());
        Binder byIds = s -> s.setArray(1, idArray);

        Map<UUID, List<Amount>> allocations = new HashMap<>();
        list(c, "select a.item_id, a.participant_id, a.amount_cents from public.item_allocations a"
                + " join public.expense_items i on i.item_id = a.item_id where i.expense_id = any(?)", byIds, r -> {
            allocations.computeIfAbsent(r.getObject(1, UUID.class), k -> new ArrayList<>())
                    .add(new Amount(r.getObject(2, UUID.class), r.getLong(3)));
            return null;
        });
        Map<UUID, List<Item>> items = new HashMap<>();
        list(c, "select item_id, expense_id, name, quantity, total_cents from public.expense_items"
                + " where expense_id = any(?) order by expense_id, position", byIds, r -> {
            UUID itemId = r.getObject(1, UUID.class);
            items.computeIfAbsent(r.getObject(2, UUID.class), k -> new ArrayList<>()).add(new Item(itemId, r.getString(3),
                    r.getInt(4), r.getLong(5), allocations.getOrDefault(itemId, List.of())));
            return null;
        });
        Map<UUID, List<Amount>> shares = amountsByExpense(c, "expense_shares", byIds);
        Map<UUID, List<Amount>> payments = amountsByExpense(c, "expense_payments", byIds);

        List<Expense> result = new ArrayList<>();
        for (Expense e : headers) {
            result.add(new Expense(e.id(), e.ownerId(), e.description(), e.category(), e.date(), e.amountCents(),
                    e.taxCents(), e.tipCents(), e.entryMethod(), e.splitMethod(), e.notes(), e.createdAt(),
                    items.getOrDefault(e.id(), List.of()), shares.getOrDefault(e.id(), List.of()),
                    payments.getOrDefault(e.id(), List.of())));
        }
        return result;
    }

    private static Map<UUID, List<Amount>> amountsByExpense(Connection c, String table, Binder byIds) throws SQLException {
        Map<UUID, List<Amount>> result = new HashMap<>();
        list(c, "select expense_id, participant_id, amount_cents from public." + table
                + " where expense_id = any(?) order by expense_id, participant_id", byIds, r -> {
            result.computeIfAbsent(r.getObject(1, UUID.class), k -> new ArrayList<>())
                    .add(new Amount(r.getObject(2, UUID.class), r.getLong(3)));
            return null;
        });
        return result;
    }

    // ---- Settlements ----

    private static final String SETTLEMENT_COLUMNS = "settlement_id, owner_id, from_participant_id, to_participant_id, amount_cents,"
            + " settled_on, method, note, status, created_at, confirmed_at";

    public void saveSettlement(Settlement s) {
        db.read(c -> update(c, "insert into public.settlements (" + SETTLEMENT_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", p -> {
            p.setObject(1, s.id());
            p.setObject(2, s.ownerId());
            p.setObject(3, s.fromParticipantId());
            p.setObject(4, s.toParticipantId());
            p.setLong(5, s.amountCents());
            p.setDate(6, Date.valueOf(s.date()));
            p.setString(7, s.method());
            p.setString(8, s.note());
            p.setString(9, s.status());
            p.setTimestamp(10, Timestamp.from(s.createdAt()));
            p.setTimestamp(11, s.confirmedAt() == null ? null : Timestamp.from(s.confirmedAt()));
        }));
    }

    public Optional<Settlement> findSettlement(UUID settlementId) {
        return db.read(c -> first(c, "select " + SETTLEMENT_COLUMNS + " from public.settlements where settlement_id = ?",
                s -> s.setObject(1, settlementId), JdbcRepository::settlement));
    }

    public List<Settlement> listSettlements(UUID ownerId) {
        return db.read(c -> list(c, "select " + SETTLEMENT_COLUMNS + " from public.settlements where owner_id = ? order by created_at desc",
                s -> s.setObject(1, ownerId), JdbcRepository::settlement));
    }

    public boolean updateSettlementStatus(UUID settlementId, String expectedStatus, String newStatus, Instant at) {
        return db.read(c -> update(c, "update public.settlements set status = ?, confirmed_at = ? where settlement_id = ? and status = ?", s -> {
            s.setString(1, newStatus);
            s.setTimestamp(2, "confirmed".equals(newStatus) ? Timestamp.from(at) : null);
            s.setObject(3, settlementId);
            s.setString(4, expectedStatus);
        }) == 1);
    }

    public void deleteSettlement(UUID settlementId) {
        db.read(c -> update(c, "delete from public.settlements where settlement_id = ?", s -> s.setObject(1, settlementId)));
    }

    // ---- Summaries and quotas ----

    public List<ShareRow> findMonthlyShares(UUID userId, YearMonth month) {
        return db.read(c -> list(c, """
                select e.expense_date, e.category, s.amount_cents
                from public.expense_shares s
                join public.participants p on p.participant_id = s.participant_id
                join public.expenses e on e.expense_id = s.expense_id
                where e.owner_id = ? and p.is_self and e.expense_date >= ? and e.expense_date < ?
                """, s -> {
            s.setObject(1, userId);
            s.setDate(2, Date.valueOf(month.atDay(1)));
            s.setDate(3, Date.valueOf(month.plusMonths(1).atDay(1)));
        }, r -> new ShareRow(r.getDate(1).toLocalDate(), r.getString(2), r.getLong(3))));
    }

    public Optional<CachedSummary> findSummary(UUID userId, YearMonth month) {
        return db.read(c -> first(c, "select stats_fingerprint, summary, created_at from public.monthly_summaries where user_id = ? and month = ?", s -> {
            s.setObject(1, userId);
            s.setDate(2, Date.valueOf(month.atDay(1)));
        }, r -> new CachedSummary(r.getString(1), r.getString(2), r.getTimestamp(3).toInstant())));
    }

    public void saveSummary(UUID userId, YearMonth month, String fingerprint, String summary, Instant at) {
        db.read(c -> update(c, """
                insert into public.monthly_summaries (summary_id, user_id, month, stats_fingerprint, summary, created_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (user_id, month) do update set stats_fingerprint = excluded.stats_fingerprint,
                    summary = excluded.summary, created_at = excluded.created_at
                """, s -> {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, userId);
            s.setDate(3, Date.valueOf(month.atDay(1)));
            s.setString(4, fingerprint);
            s.setString(5, summary);
            s.setTimestamp(6, Timestamp.from(at));
        }));
    }

    public boolean consumeQuota(UUID userId, String operation, LocalDate day, int dailyLimit) {
        // The conditional upsert is atomic, so concurrent Lambdas cannot overspend the allowance.
        return db.read(c -> first(c, """
                insert into public.usage_counters (user_id, operation, day, used) values (?, ?, ?, 1)
                on conflict (user_id, operation, day) do update set used = public.usage_counters.used + 1
                where public.usage_counters.used < ?
                returning used
                """, s -> {
            s.setObject(1, userId);
            s.setString(2, operation);
            s.setDate(3, Date.valueOf(day));
            s.setInt(4, dailyLimit);
        }, r -> r.getInt(1)).isPresent());
    }

    // ---- Helpers ----

    private static int update(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        }
    }

    private static <T> List<T> list(Connection c, String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) result.add(mapper.map(rows));
                return result;
            }
        }
    }

    private static <T> Optional<T> first(Connection c, String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        List<T> rows = list(c, sql, binder, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    private static User user(ResultSet r) throws SQLException {
        return new User(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                new Preferences(r.getBoolean(5), r.getBoolean(6), r.getString(7)), r.getTimestamp(8).toInstant());
    }

    private static Participant participant(ResultSet r) throws SQLException {
        return new Participant(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                r.getBoolean(5), r.getBoolean(6), r.getTimestamp(7).toInstant());
    }

    private static Settlement settlement(ResultSet r) throws SQLException {
        Timestamp confirmed = r.getTimestamp(11);
        return new Settlement(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                r.getObject(4, UUID.class), r.getLong(5), r.getDate(6).toLocalDate(), r.getString(7), r.getString(8),
                r.getString(9), r.getTimestamp(10).toInstant(), confirmed == null ? null : confirmed.toInstant());
    }
}
