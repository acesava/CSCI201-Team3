package edu.usc.csci201.team3;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR text into a draft receipt. Adapted from the team's OCR/ReceiptOCR prototype.
 * Several OCR passes vote on each field because any single pass misreads some receipts.
 */
final class ReceiptParser {
    record Line(String text, int x, int y, int width, int height, double confidence) {}

    record Page(String text, List<Line> lines, int imageHeight) {}

    record DraftItem(String name, int quantity, long totalCents) {}

    record Draft(String merchant, Long totalCents, Long subtotalCents, Long taxCents, Long tipCents,
                 String date, String category, List<DraftItem> items) {}

    private static final Pattern LABELED_TOTAL = Pattern.compile(
            "(?i)^\\s*(?:grand\\s+total|total\\s+due|amount\\s+due|balance\\s+due|total)\\b\\s*[:$€£\\s]*([\\d.,\\s]+)");
    private static final Pattern MONEY = Pattern.compile("(?<!\\d)(\\d+(?:[.,]\\d{3})*[.,]\\d{2}|\\d+\\s*[.,]\\s*\\d{2})(?!\\d)");
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(?:(\\d{1,3})\\s*[xX@]?\\s+)?([\\p{L}][\\p{L}\\d &'./-]*?)\\s*\\.*\\s*\\$?\\s*(\\d{1,5}[.,]\\d{2})\\s*[A-Z]?\\s*$");
    private static final Pattern NOT_ITEM = Pattern.compile(
            "(?i)\\b(sub\\s*-?total|total|tax|tip|gratuity|change|cash|visa|mastercard|amex|discover|debit|credit|card|"
                    + "balance|due|tender|payment|auth|approval|server|table|guests?|check|order|tel|phone)\\b");
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final DateTimeFormatter[] DATE_FORMATS = {
        format("M/d/uuuu"), format("M/d/uu"), format("M-d-uuuu"), format("M-d-uu"), format("uuuu-MM-dd"),
        format("uuuu/M/d"), format("d.M.uuuu"), format("d.M.uu")
    };

    private ReceiptParser() {}

    /** Combines several OCR passes of the same receipt by majority vote per field. */
    static Draft combine(List<Page> pages) {
        Map<String, Double> merchantScores = new HashMap<>();
        Map<String, Integer> merchantCounts = new HashMap<>();
        Map<String, String> merchantNames = new HashMap<>();
        List<Long> totals = new ArrayList<>();
        List<Long> subtotals = new ArrayList<>();
        List<Long> taxes = new ArrayList<>();
        List<Long> tips = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        List<DraftItem> bestItems = List.of();
        StringBuilder allText = new StringBuilder();
        for (Page page : pages) {
            allText.append(page.text()).append('\n');
            Candidate merchant = findMerchant(page.lines(), page.imageHeight());
            if (merchant != null) {
                String key = merchant.text().toLowerCase(Locale.ROOT);
                merchantScores.merge(key, merchant.score(), Math::max);
                merchantCounts.merge(key, 1, Integer::sum);
                merchantNames.putIfAbsent(key, merchant.text());
            }
            addIfPresent(totals, extractTotal(page.text()));
            addIfPresent(subtotals, labeledAmount(page.text(), "sub\\s*-?total"));
            addIfPresent(taxes, labeledAmount(page.text(), "(?:sales\\s+)?tax"));
            addIfPresent(tips, labeledAmount(page.text(), "(?:tip|gratuity)"));
            addIfPresent(dates, extractDate(page.text()));
            List<DraftItem> items = extractItems(page.text());
            if (items.size() > bestItems.size()) bestItems = items;
        }
        String merchant = null;
        double highest = 0;
        for (Map.Entry<String, Double> entry : merchantScores.entrySet()) {
            double score = entry.getValue() + 0.04 * Math.min(3, merchantCounts.get(entry.getKey()) - 1);
            if (score > highest) {
                highest = score;
                merchant = merchantNames.get(entry.getKey());
            }
        }
        // Uncertain merchant names are left blank for the user to fill in.
        if (highest < 0.55) merchant = null;
        return new Draft(merchant, mostCommon(totals), mostCommon(subtotals), mostCommon(taxes), mostCommon(tips),
                mostCommon(dates), category(allText.toString(), merchant), bestItems);
    }

    // ---- Merchant ----

    private record Candidate(String text, double score) {}

    private static Candidate findMerchant(List<Line> lines, int imageHeight) {
        if (lines.isEmpty() || imageHeight <= 0) return null;
        List<Integer> heights = new ArrayList<>();
        for (Line line : lines) heights.add(line.height());
        heights.sort(Integer::compareTo);
        double medianHeight = heights.get(heights.size() / 2);
        Candidate best = null;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.y() > imageHeight * 0.38) continue;
            String value = cleanLine(line.text());
            if (!isMerchantCandidate(value)) continue;
            double position = Math.max(0, 1 - ((double) line.y() / imageHeight) / 0.38);
            double size = Math.min(1, line.height() / Math.max(1, medianHeight * 1.8));
            double confidence = Math.max(0, Math.min(1, line.confidence() / 100));
            double context = 0;
            for (int j = i + 1; j < Math.min(lines.size(), i + 4); j++) {
                if (isAddress(lines.get(j).text())) {
                    context = 1;
                    break;
                }
            }
            double score = 0.35 * position + 0.20 * size + 0.20 * confidence + 0.25 * context;
            if (best == null || score > best.score()) best = new Candidate(value, score);
        }
        return best;
    }

    static String cleanLine(String text) {
        return text.trim().replaceAll("^[^\\p{L}\\d]+", "").replaceAll("[^\\p{L}\\d)]+$", "").replaceAll("\\s+", " ");
    }

    static boolean isAddress(String text) {
        String s = text.toLowerCase(Locale.ROOT).trim();
        return s.matches(".*\\b\\S+\\s+(road|rd|street|st|avenue|ave|drive|dr|lane|ln|blvd|boulevard|court|ct|highway|hwy)\\b.*")
                || s.matches(".*\\b\\d{5}(?:-\\d{4})?\\b.*");
    }

    static boolean isMerchantCandidate(String text) {
        if (text.length() < 3 || isAddress(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(server|check|table|ordered|subtotal|total|receipt|invoice|tax|tip|change|cash|visa|"
                + "mastercard|phone|date|time|dining|customer|thank|please|balance|amount)\\b.*")) return false;
        if (lower.contains("www.") || lower.contains("http") || lower.contains("@")) return false;
        if (text.matches(".*\\(?\\d{3}\\)?[- .]?\\d{3}[- .]?\\d{4}.*") || text.matches("^\\d+.*")) return false;
        long letters = text.chars().filter(Character::isLetter).count();
        long digits = text.chars().filter(Character::isDigit).count();
        return letters >= 3 && digits <= 2 && letters >= text.length() * 0.5;
    }

    // ---- Amounts ----

    static Long extractTotal(String text) {
        String[] lines = text.split("\\R");
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).matches("\\s*sub\\s*-?total.*")) continue;
            Matcher m = LABELED_TOTAL.matcher(line);
            if (m.find()) {
                Long amount = toCents(m.group(1));
                if (amount != null) return amount;
            }
        }
        // Some receipts print the total on the lines after the subtotal without a label.
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].toLowerCase(Locale.ROOT).contains("subtotal")) continue;
            Long last = null;
            for (int j = i + 1; j < Math.min(lines.length, i + 6); j++) {
                Matcher m = MONEY.matcher(lines[j]);
                while (m.find()) {
                    Long amount = toCents(m.group(1));
                    if (amount != null) last = amount;
                }
            }
            return last;
        }
        return null;
    }

    /** The last amount on the first line whose label matches. */
    static Long labeledAmount(String text, String labelRegex) {
        Pattern label = Pattern.compile("(?i)^\\s*" + labelRegex + "\\b.*");
        for (String line : text.split("\\R")) {
            if (!label.matcher(line).matches()) continue;
            Matcher m = MONEY.matcher(line);
            Long last = null;
            while (m.find()) last = toCents(m.group(1));
            if (last != null) return last;
        }
        return null;
    }

    /** Lines shaped like "2 Avocado Toast 12.00" before the subtotal or total. */
    static List<DraftItem> extractItems(String text) {
        List<DraftItem> items = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.toLowerCase(Locale.ROOT).matches("(sub\\s*-?total|total|amount due|balance due)\\b.*")) break;
            if (NOT_ITEM.matcher(line).find()) continue;
            Matcher m = ITEM.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(2).trim().replaceAll("[.\\s]+$", "");
            if (name.chars().filter(Character::isLetter).count() < 2) continue;
            Long cents = toCents(m.group(3));
            if (cents == null) continue;
            int quantity = m.group(1) == null ? 1 : Math.max(1, Integer.parseInt(m.group(1)));
            items.add(new DraftItem(name, quantity, cents));
            if (items.size() == 100) break;
        }
        return items;
    }

    static Long toCents(String value) {
        try {
            String s = value.replaceAll("\\s+", "");
            int comma = s.lastIndexOf(',');
            int dot = s.lastIndexOf('.');
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
            BigDecimal amount = new BigDecimal(s).setScale(2, java.math.RoundingMode.HALF_UP);
            long cents = amount.movePointRight(2).longValueExact();
            return cents < 0 || cents > Body.MAX_CENTS ? null : cents;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }

    // ---- Date and category ----

    static String extractDate(String text) {
        Matcher m = Pattern.compile("(?i)\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s*(\\d{1,2})\\s*[,'°/.-]?\\s*(\\d{2,4})\\b")
                .matcher(text);
        while (m.find()) {
            int month = 0;
            for (int i = 0; i < MONTHS.length; i++) {
                if (MONTHS[i].equalsIgnoreCase(m.group(1))) month = i + 1;
            }
            int year = Integer.parseInt(m.group(3));
            if (year < 100) year += year >= 70 ? 1900 : 2000;
            try {
                return LocalDate.of(year, month, Integer.parseInt(m.group(2))).toString();
            } catch (RuntimeException ignored) {
                // Not a real date; keep looking.
            }
        }
        m = Pattern.compile("\\b(?:\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4})\\b").matcher(text);
        while (m.find()) {
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    return LocalDate.parse(m.group(), format).toString();
                } catch (DateTimeParseException ignored) {
                    // Try the next format.
                }
            }
        }
        return null;
    }

    static String category(String text, String merchant) {
        String name = merchant == null ? "" : merchant.toLowerCase(Locale.ROOT);
        String content = text.toLowerCase(Locale.ROOT);
        if (containsAny(name, "lidl", "aldi", "costco", "walmart", "whole foods", "trader joe", "grocery",
                "supermarket", "ralphs", "vons", "target")) return "Groceries";
        if (containsAny(name, "uber", "lyft", "parking", "chevron", "shell", "metro")) return "Transportation";
        if (containsAny(name, "cvs", "walgreens", "pharmacy")) return "Health";
        if (containsAny(content, "server:", "your server", "thank you for dining", "sandwich", "burger", "pizza",
                "fries", "restaurant", "coffee", "latte") || containsAny(name, "starbucks", "chipotle", "cafe", "bakery")) {
            return "Dining";
        }
        return "Other";
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static <T> void addIfPresent(List<T> list, T value) {
        if (value != null) list.add(value);
    }

    /** Most frequent value; ties go to the earliest pass. */
    private static <T> T mostCommon(List<T> values) {
        Map<T, Integer> counts = new HashMap<>();
        T best = null;
        int bestCount = 0;
        for (T value : values) {
            int count = counts.merge(value, 1, Integer::sum);
            if (count > bestCount) {
                best = value;
                bestCount = count;
            }
        }
        return best;
    }

    private static DateTimeFormatter format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }
}
