package id.my.kadaferhidup.qrissim.webhook;

import id.my.kadaferhidup.qrissim.core.PaymentEvent;
import id.my.kadaferhidup.qrissim.json.Json;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sends payment events to merchants' webhook URLs.
 *
 * <p>Each event is signed with {@link WebhookSigner} and retried with doubling delays
 * (2, 4, 8, 16 and 32 seconds) until the merchant answers 2xx or {@link #MAX_ATTEMPTS} is
 * reached. Every try is kept in a short log the UI shows. The same event id is sent on every
 * retry so the merchant can drop duplicates.
 */
public final class WebhookDispatcher implements Consumer<PaymentEvent> {

    public static final int MAX_ATTEMPTS = 6;
    private static final int LOG_SIZE = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Sends one HTTP POST and returns the status code; throws when no answer came back. */
    @FunctionalInterface
    public interface Transport {
        int post(String url, Map<String, String> headers, String body) throws Exception;
    }

    /** Runs a task later. Production uses a scheduled executor; tests run tasks by hand. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private final Map<String, WebhookEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Deque<DeliveryAttempt> log = new ArrayDeque<>();
    private final List<Consumer<DeliveryAttempt>> watchers = new ArrayList<>();
    private final Transport transport;
    private final Scheduler scheduler;
    private final Clock clock;

    public WebhookDispatcher(Transport transport, Scheduler scheduler, Clock clock) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    public void register(WebhookEndpoint endpoint) {
        endpoints.put(endpoint.merchantId(), endpoint);
    }

    public synchronized void watch(Consumer<DeliveryAttempt> watcher) {
        watchers.add(watcher);
    }

    @Override
    public void accept(PaymentEvent event) {
        WebhookEndpoint ep = endpoints.get(event.merchantId());
        if (ep == null) {
            return;
        }
        String id = "evt_" + HexFormat.of().formatHex(randomBytes(8));
        String body = Json.write(envelope(id, event));
        scheduler.schedule(() -> attempt(ep, id, event, body, 1), Duration.ZERO);
    }

    static Duration backoff(int attemptJustFailed) {
        return Duration.ofSeconds(1L << attemptJustFailed);
    }

    private void attempt(WebhookEndpoint ep, String id, PaymentEvent event, String body, int n) {
        Instant start = clock.instant();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "qris-sim-webhook/1");
        headers.put("X-QrisSim-Event", event.type().wireName());
        headers.put("X-QrisSim-Event-Id", id);
        headers.put("X-QrisSim-Attempt", Integer.toString(n));
        headers.put(WebhookSigner.HEADER, WebhookSigner.sign(ep.secret(), start, body));

        int status = 0;
        String error = null;
        long t0 = System.nanoTime();
        try {
            status = transport.post(ep.url(), headers, body);
            if (status < 200 || status > 299) {
                error = "Merchant menjawab HTTP " + status;
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        boolean delivered = error == null;
        boolean retry = !delivered && n < MAX_ATTEMPTS;
        Instant next = retry ? start.plus(backoff(n)) : null;
        String invoiceId = event.invoice() == null ? null : event.invoice().id();
        record(new DeliveryAttempt(id, event.type().wireName(), ep.merchantId(), invoiceId, n, status, delivered,
                error, ms, start, next));
        if (retry) {
            scheduler.schedule(() -> attempt(ep, id, event, body, n + 1), backoff(n));
        }
    }

    private void record(DeliveryAttempt a) {
        List<Consumer<DeliveryAttempt>> ws;
        synchronized (this) {
            log.addFirst(a);
            while (log.size() > LOG_SIZE) {
                log.removeLast();
            }
            ws = List.copyOf(watchers);
        }
        for (Consumer<DeliveryAttempt> w : ws) {
            try {
                w.accept(a);
            } catch (RuntimeException ignored) {
                // the log is informational; a watcher error must not stop retries
            }
        }
    }

    public synchronized List<DeliveryAttempt> recent() {
        return List.copyOf(log);
    }

    static Map<String, Object> envelope(String id, PaymentEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (e.invoice() != null) {
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("id", e.invoice().id());
            inv.put("amount", e.invoice().amount());
            inv.put("status", e.invoice().status());
            inv.put("note", e.invoice().note());
            inv.put("expires_at", e.invoice().expiresAt());
            inv.put("closed_at", e.invoice().closedAt());
            data.put("invoice", inv);
        }
        if (e.payment() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.payment().id());
            p.put("rrn", e.payment().rrn());
            p.put("amount", e.payment().amount());
            p.put("payer_name", e.payment().payerName());
            p.put("paid_at", e.payment().paidAt());
            data.put("payment", p);
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", id);
        env.put("type", e.type().wireName());
        env.put("merchant_id", e.merchantId());
        env.put("data", data);
        return env;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
