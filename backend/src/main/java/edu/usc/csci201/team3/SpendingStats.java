package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.ShareRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Monthly totals, category percentages, and month-over-month change, computed with BigDecimal. */
record SpendingStats(YearMonth month, BigDecimal total, int expenseCount, List<Category> categories,
                     BigDecimal previousTotal, BigDecimal change, BigDecimal changePercent) {
    record Category(String name, BigDecimal total, BigDecimal percent, BigDecimal previousTotal) {}

    boolean hasCurrentData() {
        return expenseCount > 0;
    }

    boolean hasComparison() {
        return previousTotal != null;
    }

    static SpendingStats compute(YearMonth month, List<ShareRow> current, List<ShareRow> previous) {
        Map<String, Long> byCategory = totalsByCategory(current);
        Map<String, Long> previousByCategory = totalsByCategory(previous);
        long totalCents = byCategory.values().stream().mapToLong(Long::longValue).sum();
        long previousCents = previousByCategory.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal total = Money.dollars(totalCents);
        List<Category> categories = new ArrayList<>();
        for (Map.Entry<String, Long> entry : byCategory.entrySet()) {
            BigDecimal percent = totalCents == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(entry.getValue() * 100).divide(BigDecimal.valueOf(totalCents), 1, RoundingMode.HALF_UP);
            Long before = previousByCategory.get(entry.getKey());
            categories.add(new Category(entry.getKey(), Money.dollars(entry.getValue()), percent,
                    previous.isEmpty() ? null : Money.dollars(before == null ? 0 : before)));
        }
        categories.sort((a, b) -> b.total().compareTo(a.total()));
        // Comparisons are omitted entirely when there is no previous-month data.
        BigDecimal previousTotal = previous.isEmpty() ? null : Money.dollars(previousCents);
        BigDecimal change = previousTotal == null ? null : total.subtract(previousTotal);
        BigDecimal changePercent = previousTotal == null || previousCents <= 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(previousTotal, 1, RoundingMode.HALF_UP);
        return new SpendingStats(month, total, current.size(), categories, previousTotal, change, changePercent);
    }

    private static Map<String, Long> totalsByCategory(List<ShareRow> rows) {
        Map<String, Long> totals = new TreeMap<>();
        for (ShareRow row : rows) totals.merge(row.category(), row.amountCents(), Long::sum);
        return totals;
    }

    /**
     * Only aggregated numbers: no names, descriptions, or payment details leave the backend.
     * Amounts are exact decimal strings such as "15.00".
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("month", month.toString());
        map.put("total", total.toPlainString());
        map.put("expenseCount", expenseCount);
        map.put("categories", categories.stream().map(c -> {
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("name", c.name());
            category.put("total", c.total().toPlainString());
            category.put("percent", c.percent().toPlainString());
            if (c.previousTotal() != null) category.put("previousTotal", c.previousTotal().toPlainString());
            return category;
        }).toList());
        if (hasComparison()) {
            map.put("previousTotal", previousTotal.toPlainString());
            map.put("change", change.toPlainString());
            if (changePercent != null) map.put("changePercent", changePercent.toPlainString());
        }
        return map;
    }
}
