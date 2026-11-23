package edu.usc.csci201.team3;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Sends one text message. Implementations throw on failure; callers report per-recipient results. */
interface SmsSender {
    void send(String toE164, String message) throws IOException, InterruptedException;

    /** Twilio when TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, and TWILIO_FROM_NUMBER are set; otherwise disabled. */
    static SmsSender fromEnvironment() {
        String sid = System.getenv("TWILIO_ACCOUNT_SID");
        String token = System.getenv("TWILIO_AUTH_TOKEN");
        String from = System.getenv("TWILIO_FROM_NUMBER");
        if (sid == null || sid.isBlank() || token == null || token.isBlank() || from == null || from.isBlank()) {
            return (to, message) -> {
                throw new IllegalStateException("SMS is not configured");
            };
        }
        return new TwilioSmsSender(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), sid, token, from);
    }

    /** Twilio Programmable Messaging REST API. Trial accounts can only text verified numbers. */
    final class TwilioSmsSender implements SmsSender {
        private final HttpClient http;
        private final String accountSid;
        private final String authHeader;
        private final String from;

        TwilioSmsSender(HttpClient http, String accountSid, String authToken, String from) {
            this.http = http;
            this.accountSid = accountSid;
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
            this.from = from;
        }

        public void send(String toE164, String message) throws IOException, InterruptedException {
            String form = "To=" + encode(toE164) + "&From=" + encode(from) + "&Body=" + encode(message);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api.twilio.com/2010-04-01/Accounts/" + encode(accountSid) + "/Messages.json"))
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", authHeader)
                    .header("content-type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IOException("Twilio returned HTTP " + response.statusCode());
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
