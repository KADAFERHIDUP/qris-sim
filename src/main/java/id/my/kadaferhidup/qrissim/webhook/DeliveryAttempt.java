package id.my.kadaferhidup.qrissim.webhook;

import java.time.Instant;

/**
 * One try at delivering one event. {@code statusCode} is 0 when no HTTP answer came back;
 * {@code nextRetryAt} is null once the event is delivered or given up on.
 */
public record DeliveryAttempt(String eventId, String eventType, String merchantId, String invoiceId, int attempt,
                              int statusCode, boolean delivered, String error, long durationMs, Instant at,
                              Instant nextRetryAt) {
}
