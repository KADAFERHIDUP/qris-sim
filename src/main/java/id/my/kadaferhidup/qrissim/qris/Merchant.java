package id.my.kadaferhidup.qrissim.qris;

/**
 * The merchant data a QRIS code carries. Lengths follow the QRIS limits
 * (name 25, city 15, postal code 10) so the payload never has to truncate.
 */
public record Merchant(String id, String name, String city, String postalCode, String mcc, String nmid, String pan) {

    public Merchant {
        require(id, "id", 1, 32);
        require(name, "nama", 1, 25);
        require(city, "kota", 1, 15);
        require(postalCode, "kode pos", 1, 10);
        if (mcc == null || !mcc.matches("\\d{4}")) {
            throw new QrisException("MCC harus 4 digit");
        }
        require(nmid, "NMID", 1, 20);
        if (pan == null || !pan.matches("\\d{16,19}")) {
            throw new QrisException("PAN merchant harus 16 sampai 19 digit");
        }
    }

    private static void require(String v, String field, int min, int max) {
        if (v == null || v.isBlank() || v.length() < min || v.length() > max) {
            throw new QrisException("Kolom " + field + " harus " + min + "–" + max + " karakter");
        }
    }
}
