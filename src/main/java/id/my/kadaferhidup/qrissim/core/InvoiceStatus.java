package id.my.kadaferhidup.qrissim.core;

/** Life of a dynamic QR bill. Only PENDING can move; the other three are final. */
public enum InvoiceStatus {
    PENDING("Menunggu pembayaran"),
    PAID("Lunas"),
    EXPIRED("Kedaluwarsa"),
    CANCELLED("Dibatalkan");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinal() {
        return this != PENDING;
    }
}
