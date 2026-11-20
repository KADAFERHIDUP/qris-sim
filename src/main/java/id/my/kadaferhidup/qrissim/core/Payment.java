package id.my.kadaferhidup.qrissim.core;

import java.time.Instant;

/**
 * A settled payment. {@code rrn} is the 12-digit retrieval reference number the payer's
 * receipt shows; {@code invoiceId} is null for a static QR.
 */
public record Payment(String id, String rrn, String invoiceId, String merchantId, String merchantName,
                      String walletId, String payerName, long amount, Instant paidAt) {
}
