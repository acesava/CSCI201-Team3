package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Amount;
import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Settlement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BalanceCalculatorTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final UUID jordan = UUID.randomUUID();
    private final UUID taylor = UUID.randomUUID();

    private Expense expense(UUID payer, long total, Map<UUID, Long> shares) {
        return new Expense(UUID.randomUUID(), owner, "Dinner", "Dining", LocalDate.of(2026, 10, 10), total, 0, 0,
                "manual", "equal", null, Instant.EPOCH, List.of(),
                shares.entrySet().stream().map(e -> new Amount(e.getKey(), e.getValue())).toList(),
                List.of(new Amount(payer, total)));
    }

    private Settlement settlement(UUID from, UUID to, long cents, String status) {
        return new Settlement(UUID.randomUUID(), owner, from, to, cents, LocalDate.of(2026, 10, 11), "Venmo", null,
                status, Instant.EPOCH, null);
    }

    @Test void netBalanceFollowsTheDesignFormula() {
        var dinner = expense(alex, 2150, Map.of(alex, 717L, jordan, 717L, taylor, 716L));
        var net = BalanceCalculator.netBalances(List.of(alex, jordan, taylor), List.of(dinner),
                List.of(settlement(jordan, alex, 717, "confirmed")));
        assertEquals(2150 - 717 - 717, net.get(alex));
        assertEquals(0, net.get(jordan));
        assertEquals(-716, net.get(taylor));
    }

    @Test void pendingAndRejectedSettlementsDoNotChangeBalances() {
        var dinner = expense(alex, 1000, Map.of(alex, 500L, jordan, 500L));
        var net = BalanceCalculator.netBalances(List.of(alex, jordan), List.of(dinner),
                List.of(settlement(jordan, alex, 500, "pending"), settlement(jordan, alex, 500, "rejected")));
        assertEquals(-500, net.get(jordan));
    }

    @Test void suggestedTransfersSettleEveryone() {
        var net = BalanceCalculator.netBalances(List.of(alex, jordan, taylor), List.of(
                expense(alex, 3000, Map.of(alex, 1000L, jordan, 1000L, taylor, 1000L)),
                expense(jordan, 600, Map.of(alex, 200L, jordan, 200L, taylor, 200L))), List.of());
        var transfers = BalanceCalculator.suggestTransfers(net);
        var after = new java.util.HashMap<>(net);
        for (var t : transfers) {
            after.merge(t.fromParticipantId(), t.amountCents(), Long::sum);
            after.merge(t.toParticipantId(), -t.amountCents(), Long::sum);
        }
        assertTrue(after.values().stream().allMatch(v -> v == 0));
        assertTrue(transfers.size() <= 2);
    }
}
