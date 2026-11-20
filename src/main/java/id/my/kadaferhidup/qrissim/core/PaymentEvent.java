package id.my.kadaferhidup.qrissim.core;

/** What listeners (webhooks, live status) hear about. */
public record PaymentEvent(Type type, Invoice.View invoice, Payment payment) {

    public enum Type {
        INVOICE_CREATED("invoice.created"),
        INVOICE_PAID("invoice.paid"),
        INVOICE_EXPIRED("invoice.expired"),
        INVOICE_CANCELLED("invoice.cancelled"),
        STATIC_PAYMENT("payment.static");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public String merchantId() {
        return invoice != null ? invoice.merchantId() : payment.merchantId();
    }
}
