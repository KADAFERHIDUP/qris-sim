package id.my.kadaferhidup.qrissim.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.my.kadaferhidup.qrissim.core.Invoice;
import id.my.kadaferhidup.qrissim.core.PaymentService;
import id.my.kadaferhidup.qrissim.core.Wallet;
import id.my.kadaferhidup.qrissim.json.Json;
import id.my.kadaferhidup.qrissim.qris.Merchant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookDispatcherTest {

    static final String SECRET = "whsec_demo_1234567890";
    static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");

    record Call(String url, Map<String, String> headers, String body) {
    }

    record Pending(Runnable task, Duration delay) {
    }

    final Deque<Pending> scheduled = new ArrayDeque<>();
    final List<Call> calls = new ArrayList<>();
    final Deque<Integer> answers = new ArrayDeque<>();
    PaymentService svc;
    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        dispatcher = new WebhookDispatcher((url, headers, body) -> {
            calls.add(new Call(url, headers, body));
            Integer status = answers.poll();
            if (status == null || status < 0) {
                throw new java.net.ConnectException("Connection refused");
            }
            return status;
        }, (task, delay) -> scheduled.add(new Pending(task, delay)), clock);
        dispatcher.register(new WebhookEndpoint("M001", "http://merchant.test/webhook", SECRET));
        svc = new PaymentService(clock);
        svc.addMerchant(new Merchant("M001", "Warung Ahmad", "Lhokseumawe", "24351", "5499", "ID1026112100001",
                "9360081200000000017"));
        svc.addMerchant(new Merchant("M002", "Toko Tanpa Webhook", "Medan", "20111", "5411", "ID1026112100002",
                "9360081200000000025"));
        svc.addWallet(new Wallet("W001", "Ahmad Umraithi", "123456", 100_000));
        svc.addListener(dispatcher);
    }

    void runNext() {
        scheduled.poll().task().run();
    }

    @Test
    void deliversSignedEventOnFirstTry() {
        answers.add(200);
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        runNext();

        assertEquals(1, calls.size());
        Call c = calls.get(0);
        assertEquals("invoice.created", c.headers().get("X-QrisSim-Event"));
        assertEquals(WebhookSigner.Verdict.VALID, WebhookSigner.verify(SECRET, c.headers().get(WebhookSigner.HEADER),
                c.body(), NOW, WebhookSigner.DEFAULT_TOLERANCE));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) Json.parseObject(c.body()).get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> invoice = (Map<String, Object>) data.get("invoice");
        assertEquals(inv.id(), invoice.get("id"));
        assertEquals(10_000L, invoice.get("amount"));
        assertTrue(dispatcher.recent().get(0).delivered());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void retriesWithDoublingDelaysUntilAccepted() {
        answers.add(500);
        answers.add(-1);
        answers.add(204);
        svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        runNext();
        assertEquals(Duration.ofSeconds(2), scheduled.peek().delay());
        runNext();
        assertEquals(Duration.ofSeconds(4), scheduled.peek().delay());
        runNext();

        assertEquals(3, calls.size());
        assertTrue(scheduled.isEmpty());
        List<DeliveryAttempt> log = dispatcher.recent();
        assertTrue(log.get(0).delivered());
        assertEquals(3, log.get(0).attempt());
        assertNull(log.get(0).nextRetryAt());
        assertTrue(log.get(1).error().startsWith("ConnectException"));
        assertEquals("Merchant menjawab HTTP 500", log.get(2).error());
        // every retry carries the same event id so the merchant can de-duplicate
        assertEquals(calls.get(0).headers().get("X-QrisSim-Event-Id"), calls.get(2).headers().get("X-QrisSim-Event-Id"));
        assertEquals("3", calls.get(2).headers().get("X-QrisSim-Attempt"));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        while (!scheduled.isEmpty()) {
            runNext();
        }
        assertEquals(WebhookDispatcher.MAX_ATTEMPTS, calls.size());
        DeliveryAttempt last = dispatcher.recent().get(0);
        assertFalse(last.delivered());
        assertNull(last.nextRetryAt());
    }

    @Test
    void merchantWithoutEndpointIsSkipped() {
        svc.createInvoice("M002", 10_000, null, PaymentService.DEFAULT_TTL);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void paidEventCarriesThePayment() {
        answers.add(200);
        answers.add(200);
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        svc.pay(new PaymentService.PayRequest(inv.payload(), null, "W001", "123456", "k1"));
        runNext();
        runNext();
        Call paid = calls.get(1);
        assertEquals("invoice.paid", paid.headers().get("X-QrisSim-Event"));
        assertTrue(paid.body().contains("\"payer_name\":\"Ahmad Umraithi\""));
        assertTrue(paid.body().contains("\"status\":\"PAID\""));
    }

    @Test
    void backoffDoubles() {
        assertEquals(Duration.ofSeconds(2), WebhookDispatcher.backoff(1));
        assertEquals(Duration.ofSeconds(32), WebhookDispatcher.backoff(5));
    }
}
