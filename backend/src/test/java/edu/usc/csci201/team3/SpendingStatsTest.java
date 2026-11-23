package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.ShareRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpendingStatsTest {
    private static ShareRow row(String category, long cents) {
        return new ShareRow(LocalDate.of(2026, 11, 5), category, cents);
    }

    @Test void computesTotalsPercentagesAndChange() {
        var stats = SpendingStats.compute(YearMonth.of(2026, 11),
                List.of(row("Dining", 7500), row("Dining", 2500), row("Groceries", 5000)),
                List.of(row("Dining", 10000)));
        assertEquals(new BigDecimal("150.00"), stats.total());
        assertEquals("Dining", stats.categories().get(0).name());
        assertEquals(new BigDecimal("66.7"), stats.categories().get(0).percent());
        assertEquals(new BigDecimal("33.3"), stats.categories().get(1).percent());
        assertEquals(new BigDecimal("50.00"), stats.change());
        assertEquals(new BigDecimal("50.0"), stats.changePercent());
    }

    @Test void comparisonIsOmittedWithoutPreviousMonth() {
        var stats = SpendingStats.compute(YearMonth.of(2026, 11), List.of(row("Dining", 100)), List.of());
        assertFalse(stats.hasComparison());
        assertFalse(stats.toMap().containsKey("change"));
    }

    @Test void percentChangeNeedsAPositivePreviousTotal() {
        var stats = SpendingStats.compute(YearMonth.of(2026, 11), List.of(row("Dining", 100)), List.of(row("Dining", 0)));
        assertTrue(stats.hasComparison());
        assertNull(stats.changePercent());
    }

    @Test void noCurrentDataMeansNoSummary() {
        assertFalse(SpendingStats.compute(YearMonth.of(2026, 11), List.of(), List.of(row("Dining", 100))).hasCurrentData());
    }
}
