package id.my.kadaferhidup.qrissim.core;

import java.time.Instant;

/**
 * A dynamic QR bill. Status changes go through {@link #transition}, which allows only
 * PENDING to move, so a bill can never be paid twice or paid after it expired.
 */
public final class Invoice {

    private final String id;
    private final String merchantId;
    private final long amount;
    private final String note;
    private final String payload;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final byte[] cancelTokenHash;
    private InvoiceStatus status = InvoiceStatus.PENDING;
    private Instant closedAt;
    private String paymentId;
    private String payerName;

    Invoice(String id, String merchantId, long amount, String note, String payload, Instant createdAt, Instant expiresAt,
            String cancelToken) {
        this.cancelTokenHash = sha256(cancelToken);
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.note = note;
        this.payload = payload;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    synchronized void markPaid(Instant at, String paymentId, String payerName) {
        transition(InvoiceStatus.PAID, at);
        this.paymentId = paymentId;
        this.payerName = payerName;
    }

    synchronized void markExpired(Instant at) {
        transition(InvoiceStatus.EXPIRED, at);
    }

    synchronized void markCancelled(Instant at) {
        transition(InvoiceStatus.CANCELLED, at);
    }

    /** Only whoever created the bill holds this secret, so nobody else can cancel it. */
    boolean cancelTokenMatches(String token) {
        return token != null && java.security.MessageDigest.isEqual(cancelTokenHash, sha256(token));
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    synchronized boolean isDue(Instant now) {
        return status == InvoiceStatus.PENDING && !now.isBefore(expiresAt);
    }

    /** Throws the matching error when this bill can no longer be paid. */
    synchronized void requirePayable() {
        switch (status) {
            case PENDING -> { }
            case PAID -> throw new PaymentException(PaymentException.Code.ALREADY_PAID, "Tagihan " + id + " sudah lunas");
            case EXPIRED -> throw new PaymentException(PaymentException.Code.EXPIRED, "Tagihan " + id + " sudah kedaluwarsa");
            case CANCELLED -> throw new PaymentException(PaymentException.Code.CANCELLED, "Tagihan " + id + " dibatalkan kasir");
        }
    }

    private void transition(InvoiceStatus to, Instant at) {
        requirePayable();
        this.status = to;
        this.closedAt = at;
    }

    public synchronized View view() {
        return new View(id, merchantId, amount, note, payload, status, createdAt, expiresAt, closedAt, paymentId, payerName);
    }

    public String id() {
        return id;
    }

    public String merchantId() {
        return merchantId;
    }

    public long amount() {
        return amount;
    }

    public synchronized InvoiceStatus status() {
        return status;
    }

    /** An immutable copy for the API and for listeners on other threads. */
    public record View(String id, String merchantId, long amount, String note, String payload, InvoiceStatus status,
                       Instant createdAt, Instant expiresAt, Instant closedAt, String paymentId, String payerName) {
    }
}
