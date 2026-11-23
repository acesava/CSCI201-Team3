package edu.usc.csci201.team3;

import com.fasterxml.jackson.databind.JsonNode;
import edu.usc.csci201.team3.ReceiptParser.Page;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Drives the real handler, router, and services end to end with an in-memory repository and fake integrations. */
class ApiFlowTest {
    record Reply(int status, JsonNode json) {}

    record Text(String to, String message) {}

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);
    private Repository repo;
    private List<Text> texts;
    private AtomicReference<String> summaryReply;
    private List<String> prompts;
    private Handler handler;

    /** Subclasses run the same flows against another implementation, such as a real Postgres. */
    Repository newRepository() {
        return new InMemoryRepository();
    }

    @BeforeEach void setUp() {
        repo = newRepository();
        texts = new CopyOnWriteArrayList<>();
        summaryReply = new AtomicReference<>("You spent $15.00 this month, mostly on dining.");
        prompts = new ArrayList<>();
        SmsSender sms = (to, message) -> {
            if (to.endsWith("9999")) throw new IOException("carrier rejected");
            texts.add(new Text(to, message));
        };
        SummaryWriter writer = prompt -> {
            prompts.add(prompt);
            String reply = summaryReply.get();
            if (reply == null) throw new IOException("Gemini down");
            return reply;
        };
        ReceiptService.OcrEngine ocr = (image, psm) -> new Page("Coffee 4.50\nToast 6.00\nTax 0.50\nTotal 11.00", List.of(), image.getHeight());
        Api api = new Api(repo, new PasswordHasher(1_000), ocr, writer, sms, clock);
        handler = new Handler(new IpRateLimiter((key, expiry, limit) -> true, clock), () -> api);
    }

    private Reply call(String method, String path, String token, String body) {
        Map<String, Object> event = new HashMap<>();
        String[] split = path.split("\\?", 2);
        event.put("rawPath", split[0]);
        event.put("requestContext", Map.of("http", Map.of("method", method, "sourceIp", "192.0.2.10")));
        if (token != null) event.put("headers", Map.of("Authorization", "Bearer " + token));
        if (split.length == 2) {
            Map<String, String> query = new HashMap<>();
            for (String pair : split[1].split("&")) query.put(pair.split("=")[0], pair.split("=")[1]);
            event.put("queryStringParameters", query);
        }
        if (body != null) event.put("body", body);
        Map<String, Object> response = handler.handleRequest(event, null);
        String raw = (String) response.get("body");
        try {
            return new Reply((Integer) response.get("statusCode"), raw.isEmpty() ? null : Body.JSON.readTree(raw));
        } catch (IOException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private String register(String email) {
        Reply reply = call("POST", "/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"hunter2hunter2\",\"displayName\":\"Alex\"}");
        assertEquals(201, reply.status(), reply.json().toString());
        return reply.json().get("token").asText();
    }

    private String participant(String token, String name, String phone) {
        Reply reply = call("POST", "/participants", token,
                "{\"displayName\":\"" + name + "\"" + (phone == null ? "" : ",\"phone\":\"" + phone + "\"") + "}");
        assertEquals(201, reply.status(), reply.json().toString());
        return reply.json().get("id").asText();
    }

    private String selfId(String token) {
        for (JsonNode p : call("GET", "/participants", token, null).json().get("participants")) {
            if (p.get("isYou").asBoolean()) return p.get("id").asText();
        }
        throw new AssertionError("no self participant");
    }

    @Test void guestsCanOnlyRegisterAndLogIn() {
        assertEquals(401, call("GET", "/expenses", null, null).status());
        assertEquals(401, call("GET", "/balances", "not-a-real-token", null).status());
        assertEquals(200, call("GET", "/health", null, null).status());
    }

    @Test void registerLoginLogout() {
        register("alex@usc.edu");
        assertEquals(409, call("POST", "/auth/register", null,
                "{\"email\":\"ALEX@usc.edu\",\"password\":\"hunter2hunter2\",\"displayName\":\"Alex\"}").status());
        assertEquals(401, call("POST", "/auth/login", null, "{\"email\":\"alex@usc.edu\",\"password\":\"wrong-password\"}").status());
        assertEquals(401, call("POST", "/auth/login", null, "{\"email\":\"nobody@usc.edu\",\"password\":\"wrong-password\"}").status());
        Reply login = call("POST", "/auth/login", null, "{\"email\":\"Alex@USC.edu\",\"password\":\"hunter2hunter2\"}");
        assertEquals(200, login.status());
        String token = login.json().get("token").asText();
        assertEquals("alex@usc.edu", call("GET", "/me", token, null).json().get("email").asText());
        assertEquals(204, call("POST", "/auth/logout", token, null).status());
        assertEquals(401, call("GET", "/me", token, null).status());
    }

    @Test void registrationIsValidated() {
        assertEquals(400, call("POST", "/auth/register", null, "{\"email\":\"bad\",\"password\":\"hunter2hunter2\",\"displayName\":\"A\"}").status());
        assertEquals(400, call("POST", "/auth/register", null, "{\"email\":\"a@b.co\",\"password\":\"short\",\"displayName\":\"A\"}").status());
        assertEquals(400, call("POST", "/auth/register", null, "not json").status());
    }

    @Test void splitTextSettleAndConfirm() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", "(213) 555-0100");
        String taylor = participant(token, "Taylor", null);

        Reply created = call("POST", "/expenses", token, """
                {"description":"Bella's Cafe","category":"Dining","date":"2026-11-10","totalCents":2150,
                 "participants":["%s","%s","%s"],"notify":true}
                """.formatted(me, jordan, taylor));
        assertEquals(201, created.status(), created.json().toString());
        assertEquals(717, created.json().get("shares").get(0).get("amountCents").asLong());

        // Jordan has a phone and gets a text; Taylor has none and is skipped; the account holder is never texted.
        assertEquals(1, texts.size());
        assertEquals("+12135550100", texts.get(0).to());
        assertTrue(texts.get(0).message().contains("$7.17"));
        JsonNode notifications = created.json().get("notifications");
        assertEquals(2, notifications.size());

        JsonNode balances = call("GET", "/balances", token, null).json();
        assertEquals(1433, balances.get("owedToYouCents").asLong());
        assertEquals(0, balances.get("youOweCents").asLong());

        // A payment to the account holder is confirmed immediately because they are the recipient.
        Reply paid = call("POST", "/settlements", token, "{\"fromParticipantId\":\"" + jordan + "\",\"amountCents\":717,\"method\":\"Venmo\"}");
        assertEquals("confirmed", paid.json().get("status").asText());
        assertEquals(716, call("GET", "/balances", token, null).json().get("owedToYouCents").asLong());

        // Reminder goes to Taylor, who has no phone yet.
        assertEquals("skipped", call("POST", "/participants/" + taylor + "/remind", token, null)
                .json().get("notifications").get(0).get("status").asText());
        assertEquals(400, call("POST", "/participants/" + jordan + "/remind", token, null).status());
    }

    @Test void paymentsBetweenOtherParticipantsWaitForConfirmation() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", null);
        String taylor = participant(token, "Taylor", null);
        call("POST", "/expenses", token, """
                {"description":"Gas","date":"2026-11-11","totalCents":3000,"paidBy":"%s","participants":["%s","%s","%s"]}
                """.formatted(jordan, me, jordan, taylor));
        Reply pending = call("POST", "/settlements", token,
                "{\"fromParticipantId\":\"" + taylor + "\",\"toParticipantId\":\"" + jordan + "\",\"amountCents\":1000}");
        assertEquals("pending", pending.json().get("status").asText());
        assertEquals(1000, call("GET", "/balances", token, null).json().get("youOweCents").asLong());
        String id = pending.json().get("id").asText();
        assertEquals("confirmed", call("POST", "/settlements/" + id + "/confirm", token, null).json().get("status").asText());
        assertEquals(409, call("POST", "/settlements/" + id + "/confirm", token, null).status());
        for (JsonNode b : call("GET", "/balances", token, null).json().get("balances")) {
            if (b.get("id").asText().equals(taylor)) assertEquals(0, b.get("netCents").asLong());
        }
    }

    @Test void itemizedExpenseWithTaxAndTip() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", null);
        Reply reply = call("POST", "/expenses", token, """
                {"description":"Lunch","date":"2026-11-12","splitMethod":"itemized","taxCents":101,"tipCents":200,"entryMethod":"receipt",
                 "participants":["%s","%s"],
                 "items":[{"name":"Burger","totalCents":1200,"participantIds":["%s"]},
                          {"name":"Fries","totalCents":500,"participantIds":["%s","%s"]}]}
                """.formatted(me, jordan, me, me, jordan));
        assertEquals(201, reply.status(), reply.json().toString());
        assertEquals(2001, reply.json().get("amountCents").asLong());
        long sum = 0;
        for (JsonNode share : reply.json().get("shares")) sum += share.get("amountCents").asLong();
        assertEquals(2001, sum);
    }

    @Test void invalidSplitsShowClearErrors() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        Reply mismatch = call("POST", "/expenses", token, """
                {"description":"x","date":"2026-11-12","splitMethod":"custom","totalCents":1000,"participants":["%s"],
                 "customShares":[{"participantId":"%s","amountCents":900}]}
                """.formatted(me, me));
        assertEquals(400, mismatch.status());
        assertTrue(mismatch.json().get("error").asText().contains("$9.00"));
        assertEquals(400, call("POST", "/expenses", token, "{\"description\":\"x\",\"date\":\"2026-11-12\",\"totalCents\":10,\"participants\":[]}").status());
        assertEquals(400, call("POST", "/expenses", token, "{\"description\":\"x\",\"date\":\"2026-11-12\",\"totalCents\":10.5,\"participants\":[\"" + me + "\"]}").status());
    }

    @Test void usersCannotSeeEachOthersData() {
        String alex = register("alex@usc.edu");
        String sam = register("sam@usc.edu");
        String alexSelf = selfId(alex);
        String expenseId = call("POST", "/expenses", alex, """
                {"description":"Secret","date":"2026-11-12","totalCents":500,"participants":["%s"]}
                """.formatted(alexSelf)).json().get("id").asText();
        assertEquals(404, call("GET", "/expenses/" + expenseId, sam, null).status());
        assertEquals(404, call("DELETE", "/expenses/" + expenseId, sam, null).status());
        assertEquals(400, call("POST", "/expenses", sam, """
                {"description":"Steal","date":"2026-11-12","totalCents":500,"participants":["%s"]}
                """.formatted(alexSelf)).status());
        assertEquals(0, call("GET", "/expenses", sam, null).json().get("expenses").size());
    }

    @Test void editAndDeleteUpdateBalances() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", null);
        String id = call("POST", "/expenses", token, """
                {"description":"Pizza","date":"2026-11-12","totalCents":2000,"participants":["%s","%s"]}
                """.formatted(me, jordan)).json().get("id").asText();
        assertEquals(1000, call("GET", "/balances", token, null).json().get("owedToYouCents").asLong());
        assertEquals(200, call("PUT", "/expenses/" + id, token, """
                {"description":"Pizza","date":"2026-11-12","totalCents":3000,"participants":["%s","%s"]}
                """.formatted(me, jordan)).status());
        assertEquals(1500, call("GET", "/balances", token, null).json().get("owedToYouCents").asLong());
        assertEquals(204, call("DELETE", "/expenses/" + id, token, null).status());
        assertEquals(0, call("GET", "/balances", token, null).json().get("owedToYouCents").asLong());
        // Jordan has no history left, so removing them deletes instead of archiving.
        assertTrue(call("DELETE", "/participants/" + jordan, token, null).json().has("deleted"));
    }

    @Test void participantsWithHistoryAreArchived() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", null);
        call("POST", "/expenses", token, "{\"description\":\"x\",\"date\":\"2026-11-12\",\"totalCents\":100,\"participants\":[\"" + me + "\",\"" + jordan + "\"]}");
        assertTrue(call("DELETE", "/participants/" + jordan, token, null).json().get("archived").asBoolean());
        assertEquals(1, call("GET", "/participants", token, null).json().get("participants").size());
        assertEquals(400, call("DELETE", "/participants/" + me, token, null).status());
    }

    @Test void failedTextsDoNotBlockOthersAndQuotaIsEnforced() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String good = participant(token, "Good", "+12135550100");
        String bad = participant(token, "Bad", "+12135559999");
        Reply reply = call("POST", "/expenses", token, """
                {"description":"x","date":"2026-11-12","totalCents":300,"participants":["%s","%s","%s"],"notify":true}
                """.formatted(me, good, bad));
        Map<String, String> statuses = new HashMap<>();
        for (JsonNode n : reply.json().get("notifications")) statuses.put(n.get("name").asText(), n.get("status").asText());
        assertEquals(Map.of("Good", "sent", "Bad", "failed"), statuses);
        assertEquals(400, call("POST", "/participants", token, "{\"displayName\":\"X\",\"phone\":\"12\"}").status());

        String id = reply.json().get("id").asText();
        for (int i = 0; i < NotificationService.DAILY_SMS_LIMIT; i++) call("POST", "/expenses/" + id + "/notify", token, null);
        JsonNode last = call("POST", "/expenses/" + id + "/notify", token, null).json().get("notifications");
        for (JsonNode n : last) assertEquals("skipped", n.get("status").asText());
    }

    @Test void monthlySummaryUsesOnlyStatisticsAndCachesResults() {
        String token = register("alex@usc.edu");
        String me = selfId(token);
        String jordan = participant(token, "Jordan", null);
        assertFalse(call("GET", "/summaries/monthly?month=2026-11", token, null).json().get("available").asBoolean());

        call("POST", "/expenses", token, """
                {"description":"Secret Sushi Place","category":"Dining","date":"2026-11-10","totalCents":3000,"participants":["%s","%s"]}
                """.formatted(me, jordan));
        JsonNode first = call("GET", "/summaries/monthly?month=2026-11", token, null).json();
        assertTrue(first.get("available").asBoolean(), first.toString());
        assertFalse(first.get("comparisonAvailable").asBoolean());
        assertEquals("15.00", first.get("stats").get("total").asText());
        assertEquals(1, prompts.size());
        assertFalse(prompts.get(0).contains("Sushi"), "Descriptions must not be sent to the AI service");
        assertFalse(prompts.get(0).contains("Jordan"), "Names must not be sent to the AI service");

        call("GET", "/summaries/monthly?month=2026-11", token, null);
        assertEquals(1, prompts.size(), "Unchanged statistics should reuse the cached summary");

        summaryReply.set(null);
        call("POST", "/expenses", token, """
                {"description":"Bus","category":"Transportation","date":"2026-11-11","totalCents":500,"participants":["%s"]}
                """.formatted(me));
        JsonNode failed = call("GET", "/summaries/monthly?month=2026-11", token, null).json();
        assertFalse(failed.get("available").asBoolean());
        assertEquals("20.00", failed.get("stats").get("total").asText(), "Statistics still show when the AI fails");
    }

    @Test void receiptScanReturnsADraftAndEnforcesTheDailyLimit() throws IOException {
        String token = register("alex@usc.edu");
        BufferedImage image = new BufferedImage(40, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        String body = "{\"imageBase64\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()) + "\"}";
        Reply reply = call("POST", "/receipts/scan", token, body);
        assertEquals(200, reply.status(), reply.json().toString());
        assertEquals(1100, reply.json().get("totalCents").asLong());
        assertEquals(2, reply.json().get("items").size());
        assertEquals(400, call("POST", "/receipts/scan", token, "{\"imageBase64\":\"bm90IGFuIGltYWdl\"}").status());
        for (int i = 1; i < ReceiptService.DAILY_SCAN_LIMIT; i++) call("POST", "/receipts/scan", token, body);
        assertEquals(429, call("POST", "/receipts/scan", token, body).status());
    }

    @Test void splitPreviewWorksWithoutSaving() {
        String token = register("alex@usc.edu");
        Reply reply = call("POST", "/splits/preview", token, """
                {"totalCents":1000,"participants":["%s","%s","%s"]}
                """.formatted(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID()));
        assertEquals(200, reply.status());
        assertEquals(334, reply.json().get("shares").get(0).get("amountCents").asLong());
        assertEquals(0, call("GET", "/expenses", token, null).json().get("expenses").size());
    }

    @Test void passwordChangeAndAccountDeletion() {
        String token = register("alex@usc.edu");
        assertEquals(403, call("POST", "/me/password", token, "{\"currentPassword\":\"nope\",\"newPassword\":\"brand-new-pass\"}").status());
        String fresh = call("POST", "/me/password", token, "{\"currentPassword\":\"hunter2hunter2\",\"newPassword\":\"brand-new-pass\"}")
                .json().get("token").asText();
        assertEquals(401, call("GET", "/me", token, null).status(), "Old sessions end after a password change");
        assertEquals(200, call("PATCH", "/me", fresh, "{\"displayName\":\"Alexandra\"}").status());
        assertEquals(204, call("DELETE", "/me", fresh, "{\"password\":\"brand-new-pass\"}").status());
        assertEquals(401, call("GET", "/me", fresh, null).status(), "Deleting the account ends its sessions");
        assertEquals(401, call("POST", "/auth/login", null, "{\"email\":\"alex@usc.edu\",\"password\":\"brand-new-pass\"}").status());
        // The email is free again, which proves the old row is gone.
        register("alex@usc.edu");
    }

    @Test void unknownRoutesReturn404ForLoggedInUsers() {
        String token = register("alex@usc.edu");
        assertEquals(404, call("GET", "/groups", token, null).status());
        assertEquals(404, call("GET", "/expenses/not-a-uuid", token, null).status());
    }

    @Test void databaseOutageFailsClosed() {
        Api broken = new Api(new InMemoryRepository() {
            @Override public java.util.Optional<java.util.UUID> findSessionUser(String tokenHash, Instant now) {
                throw new Database.UnavailableException(new java.sql.SQLException("down"));
            }
        }, new PasswordHasher(1_000), (image, psm) -> null, prompt -> "", (to, message) -> {}, clock);
        handler = new Handler(new IpRateLimiter((key, expiry, limit) -> true, clock), () -> broken);
        assertEquals(503, call("GET", "/balances", "some-token", null).status());
    }
}
