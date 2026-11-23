package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Expense;
import edu.usc.csci201.team3.Models.Amount;
import edu.usc.csci201.team3.Models.Settlement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Net balance = expenses paid - assigned shares + confirmed settlements sent - confirmed settlements received.
 * Positive means the participant is owed money; negative means they owe.
 */
final class BalanceCalculator {
    record Transfer(UUID fromParticipantId, UUID toParticipantId, long amountCents) {}

    private BalanceCalculator() {}

    static Map<UUID, Long> netBalances(Collection<UUID> participants, List<Expense> expenses, List<Settlement> settlements) {
        Map<UUID, Long> net = new LinkedHashMap<>();
        for (UUID participant : participants) net.put(participant, 0L);
        for (Expense expense : expenses) {
            for (Amount payment : expense.payments()) net.merge(payment.participantId(), payment.amountCents(), Long::sum);
            for (Amount share : expense.shares()) net.merge(share.participantId(), -share.amountCents(), Long::sum);
        }
        for (Settlement settlement : settlements) {
            // Pending and rejected payments have not been confirmed by the recipient.
            if (!"confirmed".equals(settlement.status())) continue;
            net.merge(settlement.fromParticipantId(), settlement.amountCents(), Long::sum);
            net.merge(settlement.toParticipantId(), -settlement.amountCents(), Long::sum);
        }
        long total = 0;
        for (long value : net.values()) total = Math.addExact(total, value);
        if (total != 0) throw new IllegalStateException("Balances do not sum to zero");
        return net;
    }

    /** Greedy largest-debtor-to-largest-creditor plan; it uses at most (participants - 1) transfers. */
    static List<Transfer> suggestTransfers(Map<UUID, Long> net) {
        List<long[]> debtors = new ArrayList<>();
        List<long[]> creditors = new ArrayList<>();
        List<UUID> ids = new ArrayList<>(net.keySet());
        for (int i = 0; i < ids.size(); i++) {
            long value = net.get(ids.get(i));
            if (value < 0) debtors.add(new long[] {i, -value});
            if (value > 0) creditors.add(new long[] {i, value});
        }
        Comparator<long[]> largestFirst = Comparator.comparingLong((long[] entry) -> -entry[1]).thenComparingLong(entry -> entry[0]);
        debtors.sort(largestFirst);
        creditors.sort(largestFirst);
        List<Transfer> transfers = new ArrayList<>();
        int d = 0;
        int c = 0;
        while (d < debtors.size() && c < creditors.size()) {
            long[] debtor = debtors.get(d);
            long[] creditor = creditors.get(c);
            long amount = Math.min(debtor[1], creditor[1]);
            transfers.add(new Transfer(ids.get((int) debtor[0]), ids.get((int) creditor[0]), amount));
            debtor[1] -= amount;
            creditor[1] -= amount;
            if (debtor[1] == 0) d++;
            if (creditor[1] == 0) c++;
        }
        return transfers;
    }
}
