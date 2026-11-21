package id.my.kadaferhidup.qrissim.core;

import id.my.kadaferhidup.qrissim.qris.Merchant;
import id.my.kadaferhidup.qrissim.qris.QrisException;
import id.my.kadaferhidup.qrissim.qris.QrisPayload;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The simulator's ledger: merchants, wallets, dynamic bills and payments.
 *
 * <p>Every change runs under one lock, so a payment's checks (bill still open, PIN, balance)
 * and its effects (debit, credit, bill closed) happen together or not at all. Listeners are
 * told after the lock is released.
 */
public final class PaymentService {

    /** Bank Indonesia's per-transaction ceiling for QRIS. */
    public static final long MAX_AMOUNT = 10_000_000L;
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /*
     * Limits that keep a public demo from growing without end. When a map is full the
     * oldest entry goes; for bills, the oldest closed one goes first.
     */
    static final int MAX_WALLETS = 5_000;
    static final int MAX_INVOICES = 5_000;
    static final int MAX_PAYMENTS = 10_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneId.of("Asia/Jakarta"));

    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Merchant> merchants = new LinkedHashMap<>();
    private final Map<String, Long> merchantBalances = new HashMap<>();
    private final Map<String, Wallet> wallets = bounded(MAX_WALLETS);
    private final Map<String, Invoice> invoices = new LinkedHashMap<>();
    private final Map<String, Payment> payments = bounded(MAX_PAYMENTS);
    private final Map<String, Replay> idempotency = bounded(MAX_PAYMENTS);
    private final List<Consumer<PaymentEvent>> listeners = new CopyOnWriteArrayList<>();

    private record Replay(String fingerprint, Payment payment) {
    }

    /** What the payer's app sends. {@code amount} is read only for a static QR. */
    public record PayRequest(String payload, Long amount, String walletId, String pin, String idempotencyKey) {

        String fingerprint() {
            return payload + '|' + amount + '|' + walletId;
        }
    }

    public PaymentService(Clock clock) {
        this.clock = clock;
    }

    public void addListener(Consumer<PaymentEvent> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ setup

    public void addMerchant(Merchant m) {
        synchronized (lock) {
            merchants.put(m.id(), m);
            merchantBalances.putIfAbsent(m.id(), 0L);
        }
    }

    public void addWallet(Wallet w) {
        synchronized (lock) {
            wallets.put(w.id(), w);
        }
    }

    /** A fresh wallet for one visitor of the public demo, so nobody shares a balance or a PIN lock. */
    public Wallet createDemoWallet(String owner, String pin, long balance) {
        synchronized (lock) {
            String id;
            do {
                id = "W" + randomCode(9);
            } while (wallets.containsKey(id));
            Wallet w = new Wallet(id, owner, pin, balance);
            wallets.put(id, w);
            return w;
        }
    }

    /** Tops a wallet back up and clears its PIN lock. */
    public Wallet resetWallet(String walletId, long balance) {
        synchronized (lock) {
            Wallet w = wallet(walletId);
            w.reset(balance);
            return w;
        }
    }

    // ------------------------------------------------------------------ merchant side

    public Invoice.View createInvoice(String merchantId, long amount, String note, Duration ttl) {
        checkAmount(amount);
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Masa berlaku harus 1 detik sampai 24 jam");
        }
        Invoice.View view;
        synchronized (lock) {
            Merchant m = merchant(merchantId);
            Instant now = clock.instant();
            String id;
            do {
                id = "INV" + DAY.format(now) + randomCode(5);
            } while (invoices.containsKey(id));
            String payload = QrisPayload.build(m, amount, id);
            Invoice inv = new Invoice(id, m.id(), amount, note == null ? "" : note.strip(), payload, now, now.plus(ttl));
            invoices.put(id, inv);
            trimInvoices();
            view = inv.view();
        }
        publish(new PaymentEvent(PaymentEvent.Type.INVOICE_CREATED, view, null));
        return view;
    }

    public String staticQr(String merchantId) {
        synchronized (lock) {
            return QrisPayload.build(merchant(merchantId), null, null);
        }
    }

    public Invoice.View cancel(String invoiceId) {
        Invoice.View view;
        synchronized (lock) {
            Invoice inv = invoice(invoiceId);
            expireIfDue(inv, clock.instant());
            inv.markCancelled(clock.instant());
            view = inv.view();
        }
        publish(new PaymentEvent(PaymentEvent.Type.INVOICE_CANCELLED, view, null));
        return view;
    }

    public Invoice.View getInvoice(String invoiceId) {
        List<PaymentEvent> expired = new ArrayList<>();
        Invoice.View view;
        synchronized (lock) {
            Invoice inv = invoice(invoiceId);
            if (expireIfDue(inv, clock.instant())) {
                expired.add(new PaymentEvent(PaymentEvent.Type.INVOICE_EXPIRED, inv.view(), null));
            }
            view = inv.view();
        }
        expired.forEach(this::publish);
        return view;
    }

    public List<Invoice.View> recentInvoices(int limit) {
        synchronized (lock) {
            return invoices.values().stream()
                    .map(Invoice::view)
                    .sorted(Comparator.comparing(Invoice.View::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    /** Closes every open bill past its deadline. Run on a timer. */
    public int expireDue() {
        List<PaymentEvent> events = new ArrayList<>();
        synchronized (lock) {
            Instant now = clock.instant();
            for (Invoice inv : invoices.values()) {
                if (expireIfDue(inv, now)) {
                    events.add(new PaymentEvent(PaymentEvent.Type.INVOICE_EXPIRED, inv.view(), null));
                }
            }
        }
        events.forEach(this::publish);
        return events.size();
    }

    public long merchantBalance(String merchantId) {
        synchronized (lock) {
            merchant(merchantId);
            return merchantBalances.get(merchantId);
        }
    }

    // ------------------------------------------------------------------ payer side

    /** Reads a QR for the payer's confirm screen, without paying. */
    public QrisPayload inspect(String payload) {
        QrisPayload qr = parse(payload);
        synchronized (lock) {
            merchant(qr.merchantId().orElse(""));
            qr.billNumber().ifPresent(id -> {
                Invoice inv = invoice(id);
                expireIfDue(inv, clock.instant());
                inv.requirePayable();
            });
        }
        return qr;
    }

    public Payment pay(PayRequest req) {
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank() || req.idempotencyKey().length() > 64) {
            throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "Header Idempotency-Key wajib, maksimal 64 karakter");
        }
        QrisPayload qr = parse(req.payload());
        Payment payment;
        PaymentEvent event;
        synchronized (lock) {
            Replay seen = idempotency.get(req.idempotencyKey());
            if (seen != null) {
                if (!seen.fingerprint().equals(req.fingerprint())) {
                    throw new PaymentException(PaymentException.Code.IDEMPOTENCY_CONFLICT,
                            "Idempotency-Key ini sudah dipakai untuk pembayaran lain");
                }
                return seen.payment();
            }

            Instant now = clock.instant();
            Merchant m = merchant(qr.merchantId().orElse(""));
            Wallet w = wallets.get(req.walletId());
            if (w == null) {
                throw new PaymentException(PaymentException.Code.NOT_FOUND, "Dompet tidak ditemukan");
            }

            Invoice inv = null;
            long amount;
            if (qr.dynamic()) {
                inv = invoice(qr.billNumber().orElseThrow(() ->
                        new PaymentException(PaymentException.Code.INVALID_QR, "QR dinamis tanpa nomor tagihan")));
                if (!inv.merchantId().equals(m.id())) {
                    throw new PaymentException(PaymentException.Code.INVALID_QR, "Tagihan bukan milik merchant di QR");
                }
                amount = qr.amount().orElseThrow();
                if (amount != inv.amount()) {
                    throw new PaymentException(PaymentException.Code.AMOUNT_MISMATCH, "Nominal di QR beda dengan tagihan");
                }
                if (req.amount() != null && req.amount() != amount) {
                    throw new PaymentException(PaymentException.Code.AMOUNT_MISMATCH, "QR dinamis tidak boleh diubah nominalnya");
                }
                expireIfDue(inv, now);
                inv.requirePayable();
            } else {
                if (req.amount() == null) {
                    throw new PaymentException(PaymentException.Code.INVALID_REQUEST, "QR statis: isi nominal yang dibayar");
                }
                amount = req.amount();
                checkAmount(amount);
            }

            w.verifyPin(req.pin(), now);
            w.debit(amount);
            merchantBalances.merge(m.id(), amount, Long::sum);

            String paymentId = "PAY" + randomCode(10);
            payment = new Payment(paymentId, rrn(), inv == null ? null : inv.id(), m.id(), m.name(),
                    w.id(), w.owner(), amount, now);
            payments.put(paymentId, payment);
            idempotency.put(req.idempotencyKey(), new Replay(req.fingerprint(), payment));
            if (inv != null) {
                inv.markPaid(now, paymentId, w.owner());
                event = new PaymentEvent(PaymentEvent.Type.INVOICE_PAID, inv.view(), payment);
            } else {
                event = new PaymentEvent(PaymentEvent.Type.STATIC_PAYMENT, null, payment);
            }
        }
        publish(event);
        return payment;
    }

    public Wallet wallet(String walletId) {
        synchronized (lock) {
            return Optional.ofNullable(wallets.get(walletId))
                    .orElseThrow(() -> new PaymentException(PaymentException.Code.NOT_FOUND, "Dompet tidak ditemukan"));
        }
    }

    public List<Merchant> merchants() {
        synchronized (lock) {
            return List.copyOf(merchants.values());
        }
    }

    public Instant now() {
        return clock.instant();
    }

    // ------------------------------------------------------------------ internals

    private static QrisPayload parse(String payload) {
        QrisPayload qr;
        try {
            qr = QrisPayload.parse(payload);
        } catch (QrisException e) {
            throw new PaymentException(PaymentException.Code.INVALID_QR, e.getMessage());
        }
        if (!qr.guid().map(QrisPayload.GUID::equals).orElse(false)) {
            throw new PaymentException(PaymentException.Code.UNKNOWN_ISSUER,
                    "QR ini bukan dari simulator (GUID " + qr.guid().orElse("tidak ada") + ")");
        }
        return qr;
    }

    private Merchant merchant(String id) {
        Merchant m = merchants.get(id);
        if (m == null) {
            throw new PaymentException(PaymentException.Code.NOT_FOUND, "Merchant " + id + " tidak terdaftar");
        }
        return m;
    }

    private Invoice invoice(String id) {
        Invoice inv = invoices.get(Objects.requireNonNullElse(id, ""));
        if (inv == null) {
            throw new PaymentException(PaymentException.Code.NOT_FOUND, "Tagihan " + id + " tidak ditemukan");
        }
        return inv;
    }

    private void trimInvoices() {
        if (invoices.size() <= MAX_INVOICES) {
            return;
        }
        String victim = null;
        for (Invoice inv : invoices.values()) {
            if (inv.status().isFinal()) {
                victim = inv.id();
                break;
            }
        }
        invoices.remove(victim != null ? victim : invoices.keySet().iterator().next());
    }

    private static <K, V> Map<K, V> bounded(int max) {
        return new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    int invoiceCount() {
        synchronized (lock) {
            return invoices.size();
        }
    }

    private static boolean expireIfDue(Invoice inv, Instant now) {
        if (inv.isDue(now)) {
            inv.markExpired(now);
            return true;
        }
        return false;
    }

    private static void checkAmount(long amount) {
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new PaymentException(PaymentException.Code.AMOUNT_OUT_OF_RANGE,
                    "Nominal harus Rp1 sampai Rp10.000.000 per transaksi");
        }
    }

    private void publish(PaymentEvent e) {
        for (Consumer<PaymentEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // A broken listener must never undo a payment that already settled.
            }
        }
    }

    private static String randomCode(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    private static String rrn() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
