package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.Participant;
import edu.usc.csci201.team3.Models.User;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Texts participants about what they owe. Messages go out in parallel on a small worker pool,
 * and every result is collected before the Lambda invocation returns.
 */
final class NotificationService {
    record Message(Participant to, String text) {}

    static final int DAILY_SMS_LIMIT = 25;
    private static final int WORKERS = 4;
    private final Repository repo;
    private final SmsSender sms;
    private final Clock clock;

    NotificationService(Repository repo, SmsSender sms, Clock clock) {
        this.repo = repo;
        this.sms = sms;
        this.clock = clock;
    }

    /** Sends each message and reports per-recipient status; one failed number never blocks the others. */
    List<Map<String, Object>> sendAll(User user, List<Message> messages) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Message> sendable = new ArrayList<>();
        for (Message message : messages) {
            if (message.to().phone() == null) {
                results.add(result(message.to(), "skipped", "No phone number"));
            } else {
                sendable.add(message);
            }
        }
        if (sendable.isEmpty()) return results;
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Message> allowed = new ArrayList<>();
        for (Message message : sendable) {
            if (repo.consumeQuota(user.id(), "sms", today, DAILY_SMS_LIMIT)) {
                allowed.add(message);
            } else {
                results.add(result(message.to(), "skipped", "Daily text message limit reached"));
            }
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(Math.min(WORKERS, Math.max(1, allowed.size())))) {
            for (Message message : allowed) futures.add(workers.submit(() -> deliver(message)));
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get(15, TimeUnit.SECONDS));
                } catch (TimeoutException slow) {
                    futures.get(i).cancel(true);
                    results.add(result(allowed.get(i).to(), "failed", "Timed out"));
                } catch (Exception failed) {
                    results.add(result(allowed.get(i).to(), "failed", "Could not send"));
                }
            }
        }
        return results;
    }

    private Map<String, Object> deliver(Message message) {
        try {
            sms.send(message.to().phone(), message.text());
            return result(message.to(), "sent", null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(message.to(), "failed", "Interrupted");
        } catch (Exception failure) {
            // Class name only: provider errors can echo phone numbers.
            System.err.println("SMS send failed: " + failure.getClass().getSimpleName());
            return result(message.to(), "failed", "Could not send");
        }
    }

    private static Map<String, Object> result(Participant to, String status, String reason) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("participantId", to.id());
        view.put("name", to.displayName());
        view.put("status", status);
        if (reason != null) view.put("reason", reason);
        return view;
    }
}
