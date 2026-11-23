package edu.usc.csci201.team3;

import edu.usc.csci201.team3.Models.User;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Routes requests to services. Everything except /health, register, and login requires a session. */
final class Api {
    record Response(int status, Object body) {}

    private final AuthService auth;
    private final ParticipantService participants;
    private final ExpenseService expenses;
    private final ReceiptService receipts;
    private final MonthlySummaryService summaries;

    Api(Repository repo, PasswordHasher hasher, ReceiptService.OcrEngine ocr, SummaryWriter writer, SmsSender sms, Clock clock) {
        this.auth = new AuthService(repo, hasher, clock);
        this.participants = new ParticipantService(repo, clock);
        this.expenses = new ExpenseService(repo, participants, new NotificationService(repo, sms, clock), clock);
        this.receipts = new ReceiptService(ocr, repo, clock);
        this.summaries = new MonthlySummaryService(repo, writer, clock);
    }

    /** Built lazily so /health keeps working even when database settings are missing. */
    static Supplier<Api> production() {
        return new Supplier<>() {
            private Api api;

            public synchronized Api get() {
                if (api == null) {
                    api = new Api(new JdbcRepository(Database.fromEnvironment()),
                            new PasswordHasher(PasswordHasher.PRODUCTION_ITERATIONS), TesseractOcrEngine.fromEnvironment(),
                            SummaryWriter.fromEnvironment(), SmsSender.fromEnvironment(), Clock.systemUTC());
                }
                return api;
            }
        };
    }

    Response handle(Request req) {
        List<String> p;
        // Guest routes
        if (req.match("POST", "/auth/register") != null) return created(auth.register(body(req)));
        if (req.match("POST", "/auth/login") != null) return ok(auth.login(body(req)));
        if (req.match("POST", "/auth/logout") != null) {
            auth.logout(req.bearerToken());
            return noContent();
        }

        User user = auth.authenticate(req.bearerToken());

        // Profile
        if (req.match("GET", "/me") != null) return ok(Views.user(user));
        if (req.match("PATCH", "/me") != null) return ok(auth.updateProfile(user, body(req)));
        if (req.match("POST", "/me/password") != null) return ok(auth.changePassword(user, body(req)));
        if (req.match("DELETE", "/me") != null) {
            auth.deleteAccount(user, body(req));
            return noContent();
        }

        // Participants and SMS reminders
        if (req.match("GET", "/participants") != null) {
            return ok(Map.of("participants", participants.list(user, "true".equals(req.queryParam("includeArchived")))));
        }
        if (req.match("POST", "/participants") != null) return created(participants.create(user, body(req)));
        if ((p = req.match("PATCH", "/participants/{}")) != null) return ok(participants.update(user, id(p, 0), body(req)));
        if ((p = req.match("DELETE", "/participants/{}")) != null) return ok(participants.remove(user, id(p, 0)));
        if ((p = req.match("POST", "/participants/{}/remind")) != null) return ok(expenses.remind(user, id(p, 0)));

        // Expenses
        if (req.match("GET", "/expenses") != null) return ok(expenses.history(user, req.queryParam("month")));
        if (req.match("POST", "/expenses") != null) return created(expenses.create(user, body(req)));
        if ((p = req.match("GET", "/expenses/{}")) != null) return ok(expenses.get(user, id(p, 0)));
        if ((p = req.match("PUT", "/expenses/{}")) != null) return ok(expenses.update(user, id(p, 0), body(req)));
        if ((p = req.match("DELETE", "/expenses/{}")) != null) {
            expenses.delete(user, id(p, 0));
            return noContent();
        }
        if ((p = req.match("POST", "/expenses/{}/notify")) != null) return ok(expenses.notify(user, id(p, 0)));
        if (req.match("POST", "/splits/preview") != null) return ok(expenses.preview(body(req)));

        // Balances and settlements
        if (req.match("GET", "/balances") != null) return ok(expenses.balances(user));
        if (req.match("GET", "/settlements") != null) return ok(Map.of("settlements", expenses.listSettlements(user)));
        if (req.match("POST", "/settlements") != null) return created(expenses.recordSettlement(user, body(req)));
        if ((p = req.match("POST", "/settlements/{}/confirm")) != null) return ok(expenses.confirmSettlement(user, id(p, 0)));
        if ((p = req.match("POST", "/settlements/{}/reject")) != null) return ok(expenses.rejectSettlement(user, id(p, 0)));
        if ((p = req.match("DELETE", "/settlements/{}")) != null) {
            expenses.deleteSettlement(user, id(p, 0));
            return noContent();
        }

        // Receipts and AI summary
        if (req.match("POST", "/receipts/scan") != null) return ok(receipts.scan(user, body(req)));
        if (req.match("GET", "/summaries/monthly") != null) return ok(summaries.summarize(user, req.queryParam("month")));

        throw new ApiException(404, "Route not found");
    }

    private static Body body(Request req) {
        return Body.parse(req.body());
    }

    private static UUID id(List<String> params, int index) {
        try {
            return UUID.fromString(params.get(index));
        } catch (IllegalArgumentException invalid) {
            throw ApiException.notFound("Resource");
        }
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Response created(Object body) {
        return new Response(201, body);
    }

    private static Response noContent() {
        return new Response(204, null);
    }
}
