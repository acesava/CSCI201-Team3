package edu.usc.csci201.team3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SplitCalculatorTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test void equalSplitGivesLeftoverCentsToTheFirstParticipants() {
        // $21.50 among three people, as in the Split Expense design mockup.
        Map<UUID, Long> shares = SplitCalculator.equal(2150, List.of(a, b, c));
        assertEquals(List.of(717L, 717L, 716L), List.copyOf(shares.values()));
    }

    @Test void customSplitMustMatchTotalExactly() {
        ApiException error = assertThrows(ApiException.class,
                () -> SplitCalculator.custom(1000, List.of(a, b), Map.of(a, 600L, b, 300L)));
        assertTrue(error.getMessage().contains("$9.00"));
        assertEquals(Map.of(a, 600L, b, 400L), SplitCalculator.custom(1000, List.of(a, b), Map.of(a, 600L, b, 400L)));
    }

    @Test void customSplitRejectsNonParticipants() {
        assertThrows(ApiException.class, () -> SplitCalculator.custom(100, List.of(a), Map.of(b, 100L)));
    }

    @Test void itemizedSplitAddsItemsThenSharesTaxAndTipEqually() {
        var items = List.of(
                new SplitCalculator.ItemInput("Coffee", 1, 450, List.of(a)),
                new SplitCalculator.ItemInput("Toast", 1, 1200, List.of(a, b)),
                new SplitCalculator.ItemInput("Latte", 1, 500, List.of(c)));
        SplitCalculator.Itemized result = SplitCalculator.itemized(List.of(a, b, c), items, 200, 100);
        assertEquals(2450, result.totalCents());
        assertEquals(450 + 600 + 100, result.shares().get(a));
        assertEquals(600 + 100, result.shares().get(b));
        assertEquals(500 + 100, result.shares().get(c));
        assertEquals(Map.of(a, 600L, b, 600L), result.itemAllocations().get(1));
    }

    @Test void itemsMustBeAssignedToSelectedParticipants() {
        var unassigned = List.of(new SplitCalculator.ItemInput("Fries", 1, 300, List.of()));
        assertThrows(ApiException.class, () -> SplitCalculator.itemized(List.of(a), unassigned, 0, 0));
        var outsider = List.of(new SplitCalculator.ItemInput("Fries", 1, 300, List.of(b)));
        assertThrows(ApiException.class, () -> SplitCalculator.itemized(List.of(a), outsider, 0, 0));
    }

    @Test void invalidInputsAreRejected() {
        assertThrows(ApiException.class, () -> SplitCalculator.equal(100, List.of()));
        assertThrows(ApiException.class, () -> SplitCalculator.equal(0, List.of(a)));
        assertThrows(ApiException.class, () -> SplitCalculator.equal(100, List.of(a, a)));
    }

    @Test void randomItemizedSplitsAlwaysSumToTheTotalAndStayFair() {
        Random random = new Random(201);
        for (int round = 0; round < 500; round++) {
            List<UUID> people = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(6); i++) people.add(UUID.randomUUID());
            List<SplitCalculator.ItemInput> items = new ArrayList<>();
            long expected = 0;
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                List<UUID> assignees = people.stream().filter(p -> random.nextBoolean()).toList();
                if (assignees.isEmpty()) assignees = List.of(people.get(0));
                long cents = random.nextInt(10_000);
                expected += cents;
                items.add(new SplitCalculator.ItemInput("item" + i, 1, cents, assignees));
            }
            long tax = random.nextInt(500);
            long tip = random.nextInt(500);
            var result = SplitCalculator.itemized(people, items, tax, tip);
            assertEquals(expected + tax + tip, result.totalCents());
            assertEquals(result.totalCents(), SplitCalculator.sum(result.shares()));
            for (Map<UUID, Long> allocation : result.itemAllocations()) {
                long min = allocation.values().stream().mapToLong(Long::longValue).min().orElse(0);
                long max = allocation.values().stream().mapToLong(Long::longValue).max().orElse(0);
                assertTrue(max - min <= 1, "Rounding should differ by at most one cent per item");
            }
        }
    }
}
