package edu.usc.csci201.team3;

import edu.usc.csci201.team3.BalanceCalculator.Transfer;
import edu.usc.csci201.team3.Models.Amount;
import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Item;
import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.Settlement;
import edu.usc.csci201.team3.Models.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Expenses, splits, balances, settlements, and the texts that tell participants what they owe. */
final class ExpenseService {
    private record Split(long totalCents, long taxCents, long tipCents, String method, List<Item> items, List<Amount> shares) {}

    private static final Set<String> SPLIT_METHODS = Set.of("equal", "custom", "itemized");
    private static final Set<String> ENTRY_METHODS = Set.of("manual", "receipt");
    private final Repository repo;
    private final ParticipantService participants;
    private final NotificationService notifications;
    private final Clock clock;

    ExpenseService(Repository repo, ParticipantService participants, NotificationService notifications, Clock clock) {
        this.repo = repo;
        this.participants = participants;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ---- Expenses ----

    /** Saves the expense; with "notify": true, texts each participant their share. */
    Map<String, Object> create(User user, Body body) {
        List<Participant> people = participants.all(user);
        Expense expense = buildExpense(UUID.randomUUID(), user, people, body, clock.instant());
        repo.saveExpense(expense);
        Map<String, Object> view = Views.expense(expense, names(people), true);
        if (Boolean.TRUE.equals(body.optionalBoolean("notify"))) view.put("notifications", sendShares(user, people, expense));
        return view;
    }

    Map<String, Object> update(User user, UUID expenseId, Body body) {
        Expense existing = require(user, expenseId);
        List<Participant> people = participants.all(user);
        Expense updated = buildExpense(existing.id(), user, people, body, existing.createdAt());
        repo.saveExpense(updated);
        return Views.expense(updated, names(people), true);
    }

    void delete(User user, UUID expenseId) {
        require(user, expenseId);
        repo.deleteExpense(expenseId);
    }

    Map<String, Object> get(User user, UUID expenseId) {
        return Views.expense(require(user, expenseId), names(participants.all(user)), true);
    }

    /** Expense history, optionally for one month (YYYY-MM). */
    Map<String, Object> history(User user, String month) {
        LocalDate from = LocalDate.of(1970, 1, 1);
        LocalDate to = LocalDate.of(9999, 1, 1);
        if (month != null) {
            YearMonth parsed = parseMonth(month);
            from = parsed.atDay(1);
            to = parsed.plusMonths(1).atDay(1);
        }
        List<Participant> people = participants.all(user);
        UUID self = selfId(people);
        Map<UUID, String> names = names(people);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Expense e : repo.listExpenses(user.id(), from, to)) {
            Map<String, Object> view = Views.expense(e, names, false);
            view.put("yourShareCents", e.shares().stream().filter(s -> s.participantId().equals(self)).mapToLong(Amount::amountCents).sum());
            views.add(view);
        }
        return Map.of("expenses", views);
    }

    /** Texts every participant (except the account holder) their share of a saved expense. */
    Map<String, Object> notify(User user, UUID expenseId) {
        Expense expense = require(user, expenseId);
        return Map.of("notifications", sendShares(user, participants.all(user), expense));
    }

    /** Calculator only: computes shares without saving, so splitting still works without storage or AI. */
    Map<String, Object> preview(Body body) {
        Split split = computeSplit(body, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCents", split.totalCents());
        result.put("shares", split.shares().stream()
                .map(s -> Map.<String, Object>of("participantId", s.participantId(), "amountCents", s.amountCents())).toList());
        return result;
    }

    private Expense require(User user, UUID expenseId) {
        return repo.findExpense(expenseId).filter(e -> e.ownerId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("Expense"));
    }

    private Expense buildExpense(UUID id, User user, List<Participant> people, Body body, Instant createdAt) {
        Set<UUID> known = people.stream().map(Participant::id).collect(Collectors.toSet());
        Split split = computeSplit(body, known);
        String entryMethod = body.has("entryMethod") ? body.text("entryMethod", 10) : "manual";
        if (!ENTRY_METHODS.contains(entryMethod)) throw ApiException.badRequest("entryMethod must be manual or receipt");
        String category = body.has("category") ? body.text("category", 40) : "Other";
        List<Amount> payments = payments(body, split.totalCents(), known, selfId(people));
        return new Expense(id, user.id(), body.text("description", 120), category, body.date("date"),
                split.totalCents(), split.taxCents(), split.tipCents(), entryMethod, split.method(),
                body.optionalText("notes", 500), createdAt, split.items(), split.shares(), payments);
    }

    /** known == null means calculator mode: any participant ids are accepted. */
    private static Split computeSplit(Body body, Set<UUID> known) {
        String method = body.has("splitMethod") ? body.text("splitMethod", 10) : "equal";
        if (!SPLIT_METHODS.contains(method)) throw ApiException.badRequest("splitMethod must be equal, custom, or itemized");
        List<UUID> chosen = body.uuidList("participants");
        if (chosen.size() > 50) throw ApiException.badRequest("Too many participants");
        if (known != null && !known.containsAll(chosen)) throw ApiException.badRequest("Unknown participant");
        long tax = body.optionalCents("taxCents", 0);
        long tip = body.optionalCents("tipCents", 0);
        List<SplitCalculator.ItemInput> items = new ArrayList<>();
        for (Body item : body.objectList("items", 200)) {
            items.add(new SplitCalculator.ItemInput(item.text("name", 120), item.optionalInt("quantity", 1, 1, 10_000),
                    item.cents("totalCents"), item.uuidList("participantIds")));
        }
        Map<UUID, Long> shares;
        List<Map<UUID, Long>> allocations = null;
        long total;
        switch (method) {
            case "itemized" -> {
                SplitCalculator.Itemized result = SplitCalculator.itemized(chosen, items, tax, tip);
                if (body.has("totalCents") && body.cents("totalCents") != result.totalCents()) {
                    throw ApiException.badRequest("Items, tax, and tip add up to " + Money.format(result.totalCents())
                            + " but the total is " + Money.format(body.cents("totalCents")));
                }
                total = result.totalCents();
                shares = result.shares();
                allocations = result.itemAllocations();
            }
            case "custom" -> {
                total = body.cents("totalCents");
                Map<UUID, Long> amounts = new LinkedHashMap<>();
                for (Body entry : body.objectList("customShares", 50)) {
                    if (amounts.put(entry.uuid("participantId"), entry.cents("amountCents")) != null) {
                        throw ApiException.badRequest("Each participant can only have one custom amount");
                    }
                }
                shares = SplitCalculator.custom(total, chosen, amounts);
            }
            default -> {
                total = body.cents("totalCents");
                shares = SplitCalculator.equal(total, chosen);
            }
        }
        List<Item> savedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SplitCalculator.ItemInput input = items.get(i);
            // Equal and custom splits keep scanned items for reference without per-item allocations.
            List<Amount> itemAllocations = allocations == null ? List.of() : toAmounts(allocations.get(i));
            savedItems.add(new Item(UUID.randomUUID(), input.name(), input.quantity(), input.totalCents(), itemAllocations));
        }
        return new Split(total, tax, tip, method, savedItems, toAmounts(shares));
    }

    /** Defaults to the account holder paying everything; "paidBy" or "payments" override it. */
    private static List<Amount> payments(Body body, long total, Set<UUID> known, UUID self) {
        List<Body> entries = body.objectList("payments", 50);
        if (entries.isEmpty()) {
            UUID payer = body.has("paidBy") ? body.uuid("paidBy") : self;
            if (!known.contains(payer)) throw ApiException.badRequest("Unknown payer");
            return List.of(new Amount(payer, total));
        }
        Map<UUID, Long> paid = new LinkedHashMap<>();
        for (Body entry : entries) {
            UUID payer = entry.uuid("participantId");
            long cents = entry.cents("amountCents");
            if (!known.contains(payer)) throw ApiException.badRequest("Unknown payer");
            if (cents == 0) continue;
            if (paid.put(payer, cents) != null) throw ApiException.badRequest("Each payer can only be listed once");
        }
        long sum = SplitCalculator.sum(paid);
        if (sum != total) {
            throw ApiException.badRequest("Payments add up to " + Money.format(sum) + " but the total is " + Money.format(total));
        }
        return toAmounts(paid);
    }

    private static List<Amount> toAmounts(Map<UUID, Long> amounts) {
        List<Amount> result = new ArrayList<>();
        amounts.forEach((participant, cents) -> result.add(new Amount(participant, cents)));
        return result;
    }

    // ---- Balances ----

    /** Each participant's net balance, who should pay whom, and the account holder's totals. */
    Map<String, Object> balances(User user) {
        List<Participant> people = participants.all(user);
        UUID self = selfId(people);
        Map<UUID, String> names = names(people);
        Map<UUID, Long> net = net(user, people);
        List<Transfer> transfers = BalanceCalculator.suggestTransfers(net);
        long youOwe = 0;
        long owedToYou = 0;
        for (Transfer t : transfers) {
            if (t.fromParticipantId().equals(self)) youOwe += t.amountCents();
            if (t.toParticipantId().equals(self)) owedToYou += t.amountCents();
        }
        List<Map<String, Object>> balances = new ArrayList<>();
        for (Participant p : people) {
            long value = net.getOrDefault(p.id(), 0L);
            if (p.archived() && value == 0) continue;
            Map<String, Object> view = Views.participant(p);
            view.put("netCents", value);
            balances.add(view);
        }
        List<Settlement> settlements = repo.listSettlements(user.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("youOweCents", youOwe);
        result.put("owedToYouCents", owedToYou);
        result.put("balances", balances);
        result.put("suggestedTransfers", transfers.stream().map(t -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("fromParticipantId", t.fromParticipantId());
            view.put("fromName", names.get(t.fromParticipantId()));
            view.put("toParticipantId", t.toParticipantId());
            view.put("toName", names.get(t.toParticipantId()));
            view.put("amountCents", t.amountCents());
            return view;
        }).toList());
        result.put("recentSettlements", settlements.stream().limit(10).map(s -> Views.settlement(s, names)).toList());
        return result;
    }

    /** Texts one participant a reminder of what they owe the account holder. */
    Map<String, Object> remind(User user, UUID participantId) {
        List<Participant> people = participants.all(user);
        Participant target = participants.require(user, participantId);
        if (target.isSelf()) throw ApiException.badRequest("You cannot send a reminder to yourself");
        UUID self = selfId(people);
        long owed = BalanceCalculator.suggestTransfers(net(user, people)).stream()
                .filter(t -> t.fromParticipantId().equals(participantId) && t.toParticipantId().equals(self))
                .mapToLong(Transfer::amountCents).sum();
        if (owed == 0) throw ApiException.badRequest(target.displayName() + " does not owe you anything right now");
        String text = "Hi " + target.displayName() + ", a friendly reminder from " + user.displayName()
                + " on Split Together: you owe " + Money.format(owed) + ". Reply STOP to opt out.";
        return Map.of("notifications", notifications.sendAll(user, List.of(new NotificationService.Message(target, text))));
    }

    private Map<UUID, Long> net(User user, List<Participant> people) {
        return BalanceCalculator.netBalances(people.stream().map(Participant::id).toList(),
                repo.listExpenses(user.id(), LocalDate.of(1970, 1, 1), LocalDate.of(9999, 1, 1)),
                repo.listSettlements(user.id()));
    }

    private List<Map<String, Object>> sendShares(User user, List<Participant> people, Expense expense) {
        Map<UUID, Participant> byId = new HashMap<>();
        for (Participant p : people) byId.put(p.id(), p);
        String payer = expense.payments().size() == 1
                ? byId.get(expense.payments().get(0).participantId()).displayName() : "the group";
        List<NotificationService.Message> messages = new ArrayList<>();
        for (Amount share : expense.shares()) {
            Participant to = byId.get(share.participantId());
            if (to == null || to.isSelf() || share.amountCents() == 0) continue;
            boolean paidSelf = expense.payments().stream().anyMatch(p -> p.participantId().equals(to.id()));
            String text = paidSelf
                    ? "Hi " + to.displayName() + ", " + user.displayName() + " logged \"" + expense.description() + "\" ("
                            + Money.format(expense.amountCents()) + ") on Split Together. Your share is "
                            + Money.format(share.amountCents()) + ". Reply STOP to opt out."
                    : "Hi " + to.displayName() + ", " + user.displayName() + " split \"" + expense.description() + "\" ("
                            + Money.format(expense.amountCents()) + ") on Split Together. Your share is "
                            + Money.format(share.amountCents()) + ", paid by " + payer + ". Reply STOP to opt out.";
            messages.add(new NotificationService.Message(to, text));
        }
        return notifications.sendAll(user, messages);
    }

    // ---- Settlements ----

    /**
     * Records a payment made outside the app. A payment to the account holder is confirmed right away
     * because they are the recipient; payments between other participants wait for confirmation.
     */
    Map<String, Object> recordSettlement(User user, Body body) {
        List<Participant> people = participants.all(user);
        UUID self = selfId(people);
        UUID from = body.has("fromParticipantId") ? body.uuid("fromParticipantId") : self;
        UUID to = body.has("toParticipantId") ? body.uuid("toParticipantId") : self;
        Set<UUID> known = people.stream().map(Participant::id).collect(Collectors.toSet());
        if (!known.contains(from) || !known.contains(to)) throw ApiException.badRequest("Unknown participant");
        if (from.equals(to)) throw ApiException.badRequest("Choose two different people");
        long amount = body.cents("amountCents");
        if (amount <= 0) throw ApiException.badRequest("The amount must be greater than $0.00");
        Instant now = clock.instant();
        boolean confirmed = to.equals(self);
        Settlement settlement = new Settlement(UUID.randomUUID(), user.id(), from, to, amount,
                body.has("date") ? body.date("date") : LocalDate.ofInstant(now, ZoneOffset.UTC),
                body.optionalText("method", 30), body.optionalText("note", 200),
                confirmed ? "confirmed" : "pending", now, confirmed ? now : null);
        repo.saveSettlement(settlement);
        return Views.settlement(settlement, names(people));
    }

    List<Map<String, Object>> listSettlements(User user) {
        Map<UUID, String> names = names(participants.all(user));
        return repo.listSettlements(user.id()).stream().map(s -> Views.settlement(s, names)).toList();
    }

    Map<String, Object> confirmSettlement(User user, UUID settlementId) {
        return decide(user, settlementId, "confirmed");
    }

    Map<String, Object> rejectSettlement(User user, UUID settlementId) {
        return decide(user, settlementId, "rejected");
    }

    void deleteSettlement(User user, UUID settlementId) {
        requireSettlement(user, settlementId);
        repo.deleteSettlement(settlementId);
    }

    private Map<String, Object> decide(User user, UUID settlementId, String status) {
        Settlement s = requireSettlement(user, settlementId);
        Instant now = clock.instant();
        if (!repo.updateSettlementStatus(settlementId, "pending", status, now)) {
            throw ApiException.conflict("This payment is no longer pending");
        }
        Settlement updated = new Settlement(s.id(), s.ownerId(), s.fromParticipantId(), s.toParticipantId(), s.amountCents(),
                s.date(), s.method(), s.note(), status, s.createdAt(), "confirmed".equals(status) ? now : null);
        return Views.settlement(updated, names(participants.all(user)));
    }

    private Settlement requireSettlement(User user, UUID settlementId) {
        return repo.findSettlement(settlementId).filter(s -> s.ownerId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    // ---- Helpers ----

    private static UUID selfId(List<Participant> people) {
        return people.stream().filter(Participant::isSelf).findFirst()
                .orElseThrow(() -> new IllegalStateException("Account has no self participant")).id();
    }

    private static Map<UUID, String> names(List<Participant> people) {
        Map<UUID, String> names = new HashMap<>();
        for (Participant p : people) names.put(p.id(), p.isSelf() ? "You" : p.displayName());
        return names;
    }

    static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException invalid) {
            throw ApiException.badRequest("month must be in YYYY-MM format");
        }
    }
}
