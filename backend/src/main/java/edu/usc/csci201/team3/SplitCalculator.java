package edu.usc.csci201.team3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Divides an expense into per-participant shares in integer cents.
 * Every result sums exactly to the expense total.
 */
final class SplitCalculator {
    record ItemInput(String name, int quantity, long totalCents, List<UUID> memberIds) {}

    record Itemized(long totalCents, Map<UUID, Long> shares, List<Map<UUID, Long>> itemAllocations) {}

    private SplitCalculator() {}

    static Map<UUID, Long> equal(long totalCents, List<UUID> participants) {
        validateParticipants(participants);
        if (totalCents <= 0) throw ApiException.badRequest("The total must be greater than $0.00");
        Map<UUID, Long> shares = zeroShares(participants);
        distribute(totalCents, participants, shares);
        return verified(shares, totalCents);
    }

    static Map<UUID, Long> custom(long totalCents, List<UUID> participants, Map<UUID, Long> amounts) {
        validateParticipants(participants);
        if (totalCents <= 0) throw ApiException.badRequest("The total must be greater than $0.00");
        Map<UUID, Long> shares = zeroShares(participants);
        for (Map.Entry<UUID, Long> entry : amounts.entrySet()) {
            if (!shares.containsKey(entry.getKey())) {
                throw ApiException.badRequest("Custom amounts can only be assigned to selected participants");
            }
            if (entry.getValue() < 0) throw ApiException.badRequest("Custom amounts cannot be negative");
            shares.put(entry.getKey(), entry.getValue());
        }
        long sum = sum(shares);
        if (sum != totalCents) {
            throw ApiException.badRequest("Custom amounts add up to " + Money.format(sum)
                    + " but the total is " + Money.format(totalCents));
        }
        return shares;
    }

    /** Each item is divided among its assignees; tax and tip are divided equally among all participants. */
    static Itemized itemized(List<UUID> participants, List<ItemInput> items, long taxCents, long tipCents) {
        validateParticipants(participants);
        if (items.isEmpty()) throw ApiException.badRequest("Add at least one item for an itemized split");
        if (taxCents < 0 || tipCents < 0) throw ApiException.badRequest("Tax and tip cannot be negative");
        Set<UUID> allowed = new HashSet<>(participants);
        Map<UUID, Long> shares = zeroShares(participants);
        List<Map<UUID, Long>> allocations = new ArrayList<>();
        long total = 0;
        for (ItemInput item : items) {
            if (item.totalCents() < 0) throw ApiException.badRequest("Item prices cannot be negative");
            List<UUID> assignees = item.memberIds().stream().distinct().toList();
            if (assignees.isEmpty()) {
                throw ApiException.badRequest("Assign \"" + item.name() + "\" to at least one participant");
            }
            if (!allowed.containsAll(assignees)) {
                throw ApiException.badRequest("\"" + item.name() + "\" is assigned to someone who is not a participant");
            }
            Map<UUID, Long> allocation = zeroShares(assignees);
            // Extra pennies go to whoever currently owes least, so rounding never piles onto one person.
            List<UUID> order = new ArrayList<>(assignees);
            order.sort(Comparator.comparingLong(shares::get));
            distribute(item.totalCents(), order, allocation);
            allocation.forEach((member, cents) -> shares.merge(member, cents, Long::sum));
            allocations.add(allocation);
            total = Math.addExact(total, item.totalCents());
        }
        long extras = Math.addExact(taxCents, tipCents);
        Map<UUID, Long> extraShares = zeroShares(participants);
        List<UUID> order = new ArrayList<>(participants);
        order.sort(Comparator.comparingLong(shares::get));
        distribute(extras, order, extraShares);
        extraShares.forEach((member, cents) -> shares.merge(member, cents, Long::sum));
        total = Math.addExact(total, extras);
        if (total <= 0) throw ApiException.badRequest("The total must be greater than $0.00");
        if (total > Body.MAX_CENTS) throw ApiException.badRequest("The total is too large");
        return new Itemized(total, verified(shares, total), allocations);
    }

    /** Splits cents into near-equal parts; the first (cents % n) recipients in order get one extra cent. */
    private static void distribute(long cents, List<UUID> recipients, Map<UUID, Long> into) {
        int n = recipients.size();
        long base = cents / n;
        long remainder = cents % n;
        for (int i = 0; i < n; i++) {
            into.merge(recipients.get(i), base + (i < remainder ? 1 : 0), Long::sum);
        }
    }

    private static void validateParticipants(List<UUID> participants) {
        if (participants == null || participants.isEmpty()) {
            throw ApiException.badRequest("Select at least one participant");
        }
        if (new HashSet<>(participants).size() != participants.size()) {
            throw ApiException.badRequest("Each participant can only be selected once");
        }
    }

    private static Map<UUID, Long> zeroShares(List<UUID> members) {
        Map<UUID, Long> shares = new LinkedHashMap<>();
        for (UUID member : members) shares.put(member, 0L);
        return shares;
    }

    private static Map<UUID, Long> verified(Map<UUID, Long> shares, long total) {
        if (sum(shares) != total) throw new IllegalStateException("Split does not match total");
        return shares;
    }

    static long sum(Map<UUID, Long> amounts) {
        long sum = 0;
        for (long value : amounts.values()) sum = Math.addExact(sum, value);
        return sum;
    }
}
