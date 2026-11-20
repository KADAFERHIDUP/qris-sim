package id.my.kadaferhidup.qrissim.http;

import id.my.kadaferhidup.qrissim.json.Json;
import id.my.kadaferhidup.qrissim.webhook.WebhookSigner;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The merchant's own server, built in so the demo works with nothing else running. It checks
 * every webhook's signature, ignores repeats of an event it already handled, and can be told
 * to fail the next few calls so the retry schedule can be watched in the UI.
 */
public final class DemoMerchant {

    private static final int LOG_SIZE = 50;

    public record Received(String eventId, String eventType, int attempt, String verdict, boolean duplicate,
                           int answeredWith, Instant at) {
    }

    private final String secret;
    private final Clock clock;
    private final Deque<Received> log = new ArrayDeque<>();
    private final Set<String> handled = new HashSet<>();
    private int failNext;

    public DemoMerchant(String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    /** Returns the HTTP status the merchant answers with. */
    public synchronized int receive(Map<String, String> headers, String body) {
        Instant now = clock.instant();
        String id = headers.getOrDefault("x-qrissim-event-id", "?");
        String type = headers.getOrDefault("x-qrissim-event", "?");
        int attempt = parseInt(headers.get("x-qrissim-attempt"));
        WebhookSigner.Verdict v = WebhookSigner.verify(secret, headers.get(WebhookSigner.HEADER.toLowerCase()), body, now,
                WebhookSigner.DEFAULT_TOLERANCE);
        int status;
        boolean dup = false;
        if (v != WebhookSigner.Verdict.VALID) {
            status = 401;
        } else if (failNext > 0) {
            failNext--;
            status = 503;
        } else {
            dup = !handled.add(id);
            status = 200;
            if (!dup) {
                Json.parseObject(body);
            }
        }
        log.addFirst(new Received(id, type, attempt, v.name(), dup, status, now));
        while (log.size() > LOG_SIZE) {
            log.removeLast();
        }
        return status;
    }

    public synchronized void failNext(int n) {
        if (n < 0 || n > 10) {
            throw new ApiException(400, "INVALID_REQUEST", "fail_next harus 0 sampai 10");
        }
        failNext = n;
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fail_next", failNext);
        m.put("received", List.copyOf(log));
        return m;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
