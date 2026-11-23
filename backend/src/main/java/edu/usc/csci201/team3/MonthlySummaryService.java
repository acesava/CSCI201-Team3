package edu.usc.csci201.team3;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.usc.csci201.team3.Models.CachedSummary;
import edu.usc.csci201.team3.Models.ShareRow;
import edu.usc.csci201.team3.Models.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI monthly spending summary. Two worker threads load the selected and previous month concurrently,
 * Java computes every number, and Gemini only rewrites those numbers as two or three sentences.
 */
final class MonthlySummaryService {
    static final int DAILY_GENERATION_LIMIT = 10;
    private final Repository repo;
    private final SummaryWriter writer;
    private final Clock clock;

    MonthlySummaryService(Repository repo, SummaryWriter writer, Clock clock) {
        this.repo = repo;
        this.writer = writer;
        this.clock = clock;
    }

    Map<String, Object> summarize(User user, String monthParam) {
        YearMonth month = monthParam == null ? YearMonth.now(clock.withZone(ZoneOffset.UTC)) : ExpenseService.parseMonth(monthParam);
        SpendingStats stats = loadStats(user, month);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month.toString());
        if (!stats.hasCurrentData()) {
            result.put("available", false);
            result.put("message", "No expenses recorded for " + month + " yet, so a summary is not available.");
            return result;
        }
        result.put("stats", stats.toMap());
        result.put("comparisonAvailable", stats.hasComparison());
        if (!stats.hasComparison()) result.put("comparisonMessage", "No data for the previous month, so a comparison is not available.");
        String fingerprint = fingerprint(stats);
        Optional<CachedSummary> cached = repo.findSummary(user.id(), month);
        if (cached.isPresent() && cached.get().fingerprint().equals(fingerprint)) {
            return withSummary(result, cached.get().summary(), cached.get().createdAt().toString());
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!repo.consumeQuota(user.id(), "summary", today, DAILY_GENERATION_LIMIT)) {
            return unavailable(result, "You have reached today's summary limit. Your statistics are still shown above.");
        }
        try {
            String summary = writer.write(prompt(stats));
            repo.saveSummary(user.id(), month, fingerprint, summary, clock.instant());
            return withSummary(result, summary, clock.instant().toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable(result, "The AI summary is temporarily unavailable.");
        } catch (Exception failure) {
            System.err.println("Summary generation failed: " + failure.getClass().getSimpleName());
            return unavailable(result, "The AI summary is temporarily unavailable.");
        }
    }

    /** Loads both months on separate threads; both results are collected before returning. */
    SpendingStats loadStats(User user, YearMonth month) {
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<List<ShareRow>> current = workers.submit(() -> repo.findMonthlyShares(user.id(), month));
            Future<List<ShareRow>> previous = workers.submit(() -> repo.findMonthlyShares(user.id(), month.minusMonths(1)));
            return SpendingStats.compute(month, current.get(15, TimeUnit.SECONDS), previous.get(15, TimeUnit.SECONDS));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        } catch (TimeoutException slow) {
            throw new Database.UnavailableException(slow);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Database.UnavailableException(interrupted);
        }
    }

    static String prompt(SpendingStats stats) {
        String json;
        try {
            json = Body.JSON.writeValueAsString(stats.toMap());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return """
                You write short spending summaries for a bill-splitting app.
                Using ONLY the statistics below, write two or three friendly sentences about the user's share of spending for %s.
                Mention the top categories. %s
                Do not invent numbers, merchants, or advice that the statistics do not support. Amounts are US dollars.
                Statistics (JSON): %s
                """.formatted(stats.month(), stats.hasComparison()
                        ? "Compare with the previous month using the change values given."
                        : "There is no previous-month data, so do not make any month-to-month comparison.", json);
    }

    private static String fingerprint(SpendingStats stats) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Body.JSON.writeValueAsString(stats.toMap()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<String, Object> withSummary(Map<String, Object> result, String summary, String generatedAt) {
        result.put("available", true);
        result.put("summary", summary);
        result.put("generatedAt", generatedAt);
        return result;
    }

    private static Map<String, Object> unavailable(Map<String, Object> result, String message) {
        result.put("available", false);
        result.put("message", message);
        return result;
    }
}
