package id.my.kadaferhidup.qrissim.qris;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds and reads QRIS payloads in the EMVCo Merchant-Presented Mode layout.
 *
 * <p>The simulator uses its own globally unique identifier ({@value #GUID}) instead of a real
 * acquirer's, so a code made here can never be paid by a real banking app.
 */
public final class QrisPayload {

    public static final String GUID = "ID.QRISSIM.WWW";
    public static final String STATIC = "11";
    public static final String DYNAMIC = "12";
    private static final int MAX_AMOUNT_DIGITS = 13;

    private final String raw;
    private final Map<String, String> top;
    private final Map<String, Map<String, String>> nested;

    private QrisPayload(String raw, Map<String, String> top, Map<String, Map<String, String>> nested) {
        this.raw = raw;
        this.top = top;
        this.nested = nested;
    }

    // ------------------------------------------------------------------ build

    /**
     * Builds a payload. With an amount it is dynamic (point of initiation 12) and carries the
     * bill number; without one it is static (11) and the payer types the amount.
     */
    public static String build(Merchant m, Long amount, String billNumber) {
        if (amount != null && amount <= 0) {
            throw new QrisException("Nominal harus lebih dari 0");
        }
        List<Tlv> objects = new ArrayList<>();
        objects.add(new Tlv("00", "01"));
        objects.add(new Tlv("01", amount == null ? STATIC : DYNAMIC));
        objects.add(new Tlv("26", Tlv.encodeAll(List.of(
                new Tlv("00", GUID),
                new Tlv("01", m.pan()),
                new Tlv("02", m.id()),
                new Tlv("03", "UMI")))));
        objects.add(new Tlv("51", Tlv.encodeAll(List.of(
                new Tlv("00", GUID),
                new Tlv("02", m.nmid()),
                new Tlv("03", "UMI")))));
        objects.add(new Tlv("52", m.mcc()));
        objects.add(new Tlv("53", "360"));
        if (amount != null) {
            objects.add(new Tlv("54", Long.toString(amount)));
        }
        objects.add(new Tlv("58", "ID"));
        objects.add(new Tlv("59", m.name()));
        objects.add(new Tlv("60", m.city()));
        objects.add(new Tlv("61", m.postalCode()));
        if (billNumber != null) {
            objects.add(new Tlv("62", Tlv.encodeAll(List.of(
                    new Tlv("01", billNumber),
                    new Tlv("07", "KASIR01")))));
        }
        String body = Tlv.encodeAll(objects) + "6304";
        return body + Crc16.hex(body);
    }

    // ------------------------------------------------------------------ parse

    /** Reads and checks a payload. Anything outside the format throws {@link QrisException}. */
    public static QrisPayload parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new QrisException("Payload kosong");
        }
        String raw = payload.strip();
        if (raw.length() < 12 || !raw.substring(raw.length() - 8, raw.length() - 4).equals("6304")) {
            throw new QrisException("Payload harus diakhiri tag 63 (CRC) sepanjang 4 karakter");
        }
        String expected = Crc16.hex(raw.substring(0, raw.length() - 4));
        String actual = raw.substring(raw.length() - 4).toUpperCase();
        if (!expected.equals(actual)) {
            throw new QrisException("CRC tidak cocok: tertulis " + actual + ", seharusnya " + expected);
        }

        List<Tlv> objects = Tlv.parseAll(raw);
        Map<String, String> top = new LinkedHashMap<>();
        Map<String, Map<String, String>> nested = new LinkedHashMap<>();
        for (Tlv t : objects) {
            if (top.containsKey(t.tag())) {
                throw new QrisException("Tag " + t.tag() + " muncul dua kali");
            }
            top.put(t.tag(), t.value());
            int n = Integer.parseInt(t.tag());
            if ((n >= 26 && n <= 51) || n == 62) {
                Map<String, String> sub = new LinkedHashMap<>();
                for (Tlv s : Tlv.parseAll(t.value())) {
                    sub.put(s.tag(), s.value());
                }
                nested.put(t.tag(), Collections.unmodifiableMap(sub));
            }
        }

        if (!"01".equals(objects.get(0).value()) || !"00".equals(objects.get(0).tag())) {
            throw new QrisException("Tag pertama harus 00 bernilai 01");
        }
        String poi = top.get("01");
        if (!STATIC.equals(poi) && !DYNAMIC.equals(poi)) {
            throw new QrisException("Tag 01 harus 11 (statis) atau 12 (dinamis)");
        }
        if (!"360".equals(top.get("53"))) {
            throw new QrisException("Mata uang harus 360 (rupiah)");
        }
        if (!"ID".equals(top.get("58"))) {
            throw new QrisException("Kode negara harus ID");
        }
        if (top.get("59") == null || top.get("60") == null) {
            throw new QrisException("Nama dan kota merchant wajib ada");
        }
        if (DYNAMIC.equals(poi) && top.get("54") == null) {
            throw new QrisException("QR dinamis wajib membawa nominal (tag 54)");
        }
        if (top.get("54") != null) {
            parseAmount(top.get("54"));
        }
        return new QrisPayload(raw, Collections.unmodifiableMap(top), Collections.unmodifiableMap(nested));
    }

    static long parseAmount(String v) {
        if (!v.matches("\\d{1," + MAX_AMOUNT_DIGITS + "}(\\.00?)?")) {
            throw new QrisException("Nominal tidak valid: " + v + " (rupiah tanpa sen)");
        }
        long amount = Long.parseLong(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
        if (amount <= 0) {
            throw new QrisException("Nominal harus lebih dari 0");
        }
        return amount;
    }

    // ------------------------------------------------------------------ read

    public String raw() {
        return raw;
    }

    public boolean dynamic() {
        return DYNAMIC.equals(top.get("01"));
    }

    public Optional<Long> amount() {
        return Optional.ofNullable(top.get("54")).map(QrisPayload::parseAmount);
    }

    public String merchantName() {
        return top.get("59");
    }

    public String city() {
        return top.get("60");
    }

    public String postalCode() {
        return top.get("61");
    }

    public String mcc() {
        return top.get("52");
    }

    public String crc() {
        return top.get("63");
    }

    /** Merchant id from tag 26/02, the account this simulator routes the money to. */
    public Optional<String> merchantId() {
        return sub("26", "02");
    }

    public Optional<String> guid() {
        return sub("26", "00");
    }

    public Optional<String> nmid() {
        return sub("51", "02");
    }

    public Optional<String> billNumber() {
        return sub("62", "01");
    }

    public Map<String, String> fields() {
        return top;
    }

    public Map<String, Map<String, String>> templates() {
        return nested;
    }

    private Optional<String> sub(String tag, String subTag) {
        Map<String, String> m = nested.get(tag);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(subTag));
    }
}
