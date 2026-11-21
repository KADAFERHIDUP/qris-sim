package id.my.kadaferhidup.qrissim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.my.kadaferhidup.qrissim.core.PaymentService.PayRequest;
import id.my.kadaferhidup.qrissim.qris.Crc16;
import id.my.kadaferhidup.qrissim.qris.Merchant;
import id.my.kadaferhidup.qrissim.qris.QrisPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    static final Merchant WARUNG = new Merchant("M001", "Warung Ahmad", "Lhokseumawe", "24351", "5499",
            "ID1026112100001", "9360081200000000017");
    static final String PIN = "123456";

    MutableClock clock;
    PaymentService svc;
    List<PaymentEvent> events;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-20T17:00:00Z"));
        svc = new PaymentService(clock);
        svc.addMerchant(WARUNG);
        svc.addWallet(new Wallet("W001", "Ahmad Umraithi", PIN, 100_000));
        events = new ArrayList<>();
        svc.addListener(events::add);
    }

    PayRequest pay(String payload, String key) {
        return new PayRequest(payload, null, "W001", PIN, key);
    }

    PaymentException.Code codeOf(Runnable r) {
        return assertThrows(PaymentException.class, r::run).code();
    }

    @Test
    void createdInvoiceCarriesItsIdInTheQr() {
        Invoice.View inv = svc.createInvoice("M001", 25_000, "Nasi + teh", PaymentService.DEFAULT_TTL);
        QrisPayload qr = QrisPayload.parse(inv.payload());
        assertEquals(InvoiceStatus.PENDING, inv.status());
        assertEquals(inv.id(), qr.billNumber().orElseThrow());
        assertEquals(25_000L, (long) qr.amount().orElseThrow());
        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), inv.expiresAt());
        assertEquals(PaymentEvent.Type.INVOICE_CREATED, events.get(0).type());
    }

    @Test
    void payingMovesMoneyAndClosesTheBill() {
        Invoice.View inv = svc.createInvoice("M001", 25_000, null, PaymentService.DEFAULT_TTL);
        Payment p = svc.pay(pay(inv.payload(), "k1"));

        assertEquals(75_000, svc.wallet("W001").balance());
        assertEquals(25_000, svc.merchantBalance("M001"));
        assertEquals(12, p.rrn().length());
        Invoice.View after = svc.getInvoice(inv.id());
        assertEquals(InvoiceStatus.PAID, after.status());
        assertEquals(p.id(), after.paymentId());
        assertEquals("Ahmad Umraithi", after.payerName());
        assertEquals(PaymentEvent.Type.INVOICE_PAID, events.get(events.size() - 1).type());
    }

    @Test
    void sameIdempotencyKeyReturnsTheSamePaymentOnce() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        Payment first = svc.pay(pay(inv.payload(), "retry-me"));
        Payment again = svc.pay(pay(inv.payload(), "retry-me"));

        assertSame(first, again);
        assertEquals(90_000, svc.wallet("W001").balance(), "didebit sekali saja");
    }

    @Test
    void reusedKeyForAnotherPaymentIsRefused() {
        Invoice.View a = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        Invoice.View b = svc.createInvoice("M001", 20_000, null, PaymentService.DEFAULT_TTL);
        svc.pay(pay(a.payload(), "k"));
        assertEquals(PaymentException.Code.IDEMPOTENCY_CONFLICT, codeOf(() -> svc.pay(pay(b.payload(), "k"))));
    }

    @Test
    void paidBillCannotBePaidAgain() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        svc.pay(pay(inv.payload(), "k1"));
        assertEquals(PaymentException.Code.ALREADY_PAID, codeOf(() -> svc.pay(pay(inv.payload(), "k2"))));
        assertEquals(90_000, svc.wallet("W001").balance());
    }

    @Test
    void billExpiresAfterItsDeadline() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(15));
        assertEquals(PaymentException.Code.EXPIRED, codeOf(() -> svc.pay(pay(inv.payload(), "k1"))));
        assertEquals(InvoiceStatus.EXPIRED, svc.getInvoice(inv.id()).status());
        assertEquals(100_000, svc.wallet("W001").balance());
    }

    @Test
    void sweepExpiresOnlyOverdueBills() {
        svc.createInvoice("M001", 1_000, null, Duration.ofMinutes(1));
        svc.createInvoice("M001", 2_000, null, Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, svc.expireDue());
        assertEquals(0, svc.expireDue());
        assertEquals(PaymentEvent.Type.INVOICE_EXPIRED, events.get(events.size() - 1).type());
    }

    @Test
    void cancelledBillCannotBePaid() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        svc.cancel(inv.id());
        assertEquals(PaymentException.Code.CANCELLED, codeOf(() -> svc.pay(pay(inv.payload(), "k1"))));
    }

    @Test
    void threeWrongPinsLockTheWallet() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        PayRequest wrong = new PayRequest(inv.payload(), null, "W001", "000000", "x");
        assertEquals(PaymentException.Code.WRONG_PIN, codeOf(() -> svc.pay(wrong)));
        assertEquals(PaymentException.Code.WRONG_PIN, codeOf(() -> svc.pay(wrong)));
        assertEquals(PaymentException.Code.PIN_LOCKED, codeOf(() -> svc.pay(wrong)));
        // even the right PIN is refused while locked
        assertEquals(PaymentException.Code.PIN_LOCKED, codeOf(() -> svc.pay(pay(inv.payload(), "y"))));

        clock.advance(Wallet.LOCK_DURATION);
        Payment p = svc.pay(pay(inv.payload(), "z"));
        assertEquals(10_000, p.amount());
    }

    @Test
    void insufficientFundsLeavesTheBillOpen() {
        Invoice.View inv = svc.createInvoice("M001", 150_000, null, PaymentService.DEFAULT_TTL);
        assertEquals(PaymentException.Code.INSUFFICIENT_FUNDS, codeOf(() -> svc.pay(pay(inv.payload(), "k1"))));
        assertEquals(InvoiceStatus.PENDING, svc.getInvoice(inv.id()).status());
        assertEquals(0, svc.merchantBalance("M001"));
    }

    @Test
    void staticQrNeedsTheAmountFromThePayer() {
        String qr = svc.staticQr("M001");
        assertEquals(PaymentException.Code.INVALID_REQUEST, codeOf(() -> svc.pay(pay(qr, "k1"))));

        Payment p = svc.pay(new PayRequest(qr, 7_500L, "W001", PIN, "k2"));
        assertNull(p.invoiceId());
        assertEquals(7_500, svc.merchantBalance("M001"));
        assertEquals(PaymentEvent.Type.STATIC_PAYMENT, events.get(events.size() - 1).type());
    }

    @Test
    void dynamicAmountCannotBeOverridden() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        PayRequest cheaper = new PayRequest(inv.payload(), 1_000L, "W001", PIN, "k1");
        assertEquals(PaymentException.Code.AMOUNT_MISMATCH, codeOf(() -> svc.pay(cheaper)));
    }

    @Test
    void amountsOutsideTheQrisLimitAreRefused() {
        assertEquals(PaymentException.Code.AMOUNT_OUT_OF_RANGE,
                codeOf(() -> svc.createInvoice("M001", 10_000_001, null, PaymentService.DEFAULT_TTL)));
        assertEquals(PaymentException.Code.AMOUNT_OUT_OF_RANGE,
                codeOf(() -> svc.createInvoice("M001", 0, null, PaymentService.DEFAULT_TTL)));
    }

    @Test
    void qrFromAnotherIssuerIsRefused() {
        String body = "000201" + "010211" + "2633" + "0014ID.CO.QRIS.WWW" + "0204M001" + "0303UMI" + "5303360" + "5802ID"
                + "5906Toko A" + "6005Medan" + "6304";
        String foreign = body + Crc16.hex(body);
        assertEquals(PaymentException.Code.UNKNOWN_ISSUER, codeOf(() -> svc.inspect(foreign)));
    }

    @Test
    void brokenQrIsReportedAsInvalid() {
        assertEquals(PaymentException.Code.INVALID_QR, codeOf(() -> svc.inspect("bukan qris")));
    }

    @Test
    void missingIdempotencyKeyIsRefused() {
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        assertEquals(PaymentException.Code.INVALID_REQUEST, codeOf(() -> svc.pay(pay(inv.payload(), " "))));
    }

    @Test
    void racingPayersSettleTheBillExactlyOnce() throws Exception {
        svc.addWallet(new Wallet("W002", "Ahmad Umraithi", PIN, 1_000_000));
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger alreadyPaid = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String key = "race-" + i;
            String wallet = i % 2 == 0 ? "W001" : "W002";
            pool.submit(() -> {
                start.await();
                try {
                    svc.pay(new PayRequest(inv.payload(), null, wallet, PIN, key));
                    ok.incrementAndGet();
                } catch (PaymentException e) {
                    if (e.code() == PaymentException.Code.ALREADY_PAID) {
                        alreadyPaid.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, ok.get());
        assertEquals(threads - 1, alreadyPaid.get());
        assertEquals(10_000, svc.merchantBalance("M001"));
        assertEquals(1_090_000, svc.wallet("W001").balance() + svc.wallet("W002").balance());
    }

    @Test
    void invoiceIdsAreUnique() {
        Invoice.View a = svc.createInvoice("M001", 1_000, null, PaymentService.DEFAULT_TTL);
        Invoice.View b = svc.createInvoice("M001", 1_000, null, PaymentService.DEFAULT_TTL);
        assertNotEquals(a.id(), b.id());
        assertTrue(a.id().startsWith("INV261121"));
    }

    @Test
    void demoWalletsAreSeparate() {
        PaymentService.IssuedWallet a = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        PaymentService.IssuedWallet b = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        assertNotEquals(a.wallet().id(), b.wallet().id());
        assertNotEquals(a.token(), b.token());

        Invoice.View inv = svc.createInvoice("M001", 1_000, null, PaymentService.DEFAULT_TTL);
        for (String key : new String[] {"x1", "x2", "x3"}) {
            assertThrows(PaymentException.class,
                    () -> svc.pay(new PayRequest(inv.payload(), null, a.wallet().id(), "000000", key, a.token())));
        }
        assertTrue(a.wallet().lockedAt(clock.instant()));
        // a locked visitor never blocks the next one
        Payment p = svc.pay(new PayRequest(inv.payload(), null, b.wallet().id(), PIN, "y", b.token()));
        assertEquals(b.wallet().id(), p.walletId());
    }

    @Test
    void walletNeedsItsOwnersToken() {
        PaymentService.IssuedWallet a = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        PaymentService.IssuedWallet b = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        Invoice.View inv = svc.createInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        String id = a.wallet().id();

        // knowing the id and the demo PIN is not enough to spend from someone else's wallet
        assertEquals(PaymentException.Code.NOT_FOUND, codeOf(() -> svc.pay(new PayRequest(inv.payload(), null, id, PIN, "k"))));
        assertEquals(PaymentException.Code.NOT_FOUND,
                codeOf(() -> svc.pay(new PayRequest(inv.payload(), null, id, PIN, "k", b.token()))));
        assertEquals(PaymentException.Code.NOT_FOUND, codeOf(() -> svc.ownedWallet(id, "salah")));
        assertEquals(PaymentException.Code.NOT_FOUND, codeOf(() -> svc.resetWallet(id, b.token(), 999_999)));
        assertEquals(50_000, a.wallet().balance());

        svc.pay(new PayRequest(inv.payload(), null, id, PIN, "k", a.token()));
        assertEquals(40_000, svc.ownedWallet(id, a.token()).balance());
    }

    @Test
    void idempotencyKeysBelongToOneWallet() {
        PaymentService.IssuedWallet a = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        PaymentService.IssuedWallet b = svc.createDemoWallet("Ahmad Umraithi", PIN, 50_000);
        String qr = svc.staticQr("M001");
        Payment pa = svc.pay(new PayRequest(qr, 1_000L, a.wallet().id(), PIN, "sama", a.token()));
        // the same key from another wallet is a separate payment, not a replay or a conflict
        Payment pb = svc.pay(new PayRequest(qr, 1_000L, b.wallet().id(), PIN, "sama", b.token()));
        assertNotEquals(pa.id(), pb.id());
        assertEquals(49_000, b.wallet().balance());
    }

    @Test
    void onlyTheCreatorCanCancelABill() {
        PaymentService.OpenedInvoice opened = svc.openInvoice("M001", 10_000, null, PaymentService.DEFAULT_TTL);
        String id = opened.invoice().id();
        assertEquals(PaymentException.Code.FORBIDDEN, codeOf(() -> svc.cancel(id, null)));
        assertEquals(PaymentException.Code.FORBIDDEN, codeOf(() -> svc.cancel(id, "tebakan")));
        assertEquals(InvoiceStatus.PENDING, svc.getInvoice(id).status());
        assertEquals(InvoiceStatus.CANCELLED, svc.cancel(id, opened.cancelToken()).status());
    }

    @Test
    void resetRefillsAndUnlocks() {
        PaymentService.IssuedWallet issued = svc.createDemoWallet("Ahmad Umraithi", PIN, 20_000);
        Wallet w = issued.wallet();
        Invoice.View inv = svc.createInvoice("M001", 15_000, null, PaymentService.DEFAULT_TTL);
        svc.pay(new PayRequest(inv.payload(), null, w.id(), PIN, "k", issued.token()));
        for (String key : new String[] {"a", "b", "c"}) {
            assertThrows(PaymentException.class,
                    () -> svc.pay(new PayRequest(svc.staticQr("M001"), 1_000L, w.id(), "111111", key, issued.token())));
        }
        svc.resetWallet(w.id(), issued.token(), 20_000);
        assertEquals(20_000, w.balance());
        assertTrue(!w.lockedAt(clock.instant()));
    }

    @Test
    void invoiceStoreStaysBounded() {
        Invoice.View first = svc.createInvoice("M001", 1_000, null, PaymentService.DEFAULT_TTL);
        svc.cancel(first.id());
        for (int i = 0; i < PaymentService.MAX_INVOICES; i++) {
            svc.createInvoice("M001", 1_000, null, PaymentService.DEFAULT_TTL);
        }
        assertEquals(PaymentService.MAX_INVOICES, svc.invoiceCount());
        // the closed bill is the one dropped, open ones stay payable
        assertEquals(PaymentException.Code.NOT_FOUND, codeOf(() -> svc.getInvoice(first.id())));
    }
}
