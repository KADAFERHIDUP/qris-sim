package id.my.kadaferhidup.qrissim.qris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class QrisPayloadTest {

    static final Merchant WARUNG = new Merchant("M001", "Warung Ahmad", "Lhokseumawe", "24351", "5499",
            "ID1026112100001", "9360081200000000017");

    @Test
    void dynamicPayloadRoundTrips() {
        String raw = QrisPayload.build(WARUNG, 25_000L, "INV-0001");
        QrisPayload p = QrisPayload.parse(raw);

        assertTrue(p.dynamic());
        assertEquals(Optional.of(25_000L), p.amount());
        assertEquals("Warung Ahmad", p.merchantName());
        assertEquals("Lhokseumawe", p.city());
        assertEquals(Optional.of("M001"), p.merchantId());
        assertEquals(Optional.of("INV-0001"), p.billNumber());
        assertEquals(Optional.of(QrisPayload.GUID), p.guid());
        assertEquals(Optional.of("ID1026112100001"), p.nmid());
    }

    @Test
    void staticPayloadHasNoAmount() {
        QrisPayload p = QrisPayload.parse(QrisPayload.build(WARUNG, null, null));
        assertFalse(p.dynamic());
        assertEquals(Optional.empty(), p.amount());
        assertEquals(Optional.empty(), p.billNumber());
    }

    @Test
    void payloadStartsWithFormatAndEndsWithCrc() {
        String raw = QrisPayload.build(WARUNG, 10_000L, "INV-0002");
        assertTrue(raw.startsWith("000201010212"));
        assertEquals(Crc16.hex(raw.substring(0, raw.length() - 4)), raw.substring(raw.length() - 4));
    }

    @Test
    void tamperedAmountFailsCrc() {
        String raw = QrisPayload.build(WARUNG, 10_000L, "INV-0003");
        String tampered = raw.replace("540510000", "540590000");
        QrisException e = assertThrows(QrisException.class, () -> QrisPayload.parse(tampered));
        assertTrue(e.getMessage().startsWith("CRC tidak cocok"));
    }

    @Test
    void lowerCaseCrcIsAccepted() {
        String raw = QrisPayload.build(WARUNG, 5_000L, "INV-0004");
        String lower = raw.substring(0, raw.length() - 4) + raw.substring(raw.length() - 4).toLowerCase();
        assertEquals(Optional.of(5_000L), QrisPayload.parse(lower).amount());
    }

    @Test
    void dynamicWithoutAmountIsRejected() {
        String body = "000201" + "010212" + "5303360" + "5802ID" + "5906Toko A" + "6005Medan" + "6304";
        String raw = body + Crc16.hex(body);
        QrisException e = assertThrows(QrisException.class, () -> QrisPayload.parse(raw));
        assertTrue(e.getMessage().contains("tag 54"));
    }

    @Test
    void foreignCurrencyIsRejected() {
        String body = "000201" + "010211" + "5303840" + "5802ID" + "5906Toko A" + "6005Medan" + "6304";
        String raw = body + Crc16.hex(body);
        QrisException e = assertThrows(QrisException.class, () -> QrisPayload.parse(raw));
        assertTrue(e.getMessage().contains("rupiah"));
    }

    @Test
    void duplicateTagIsRejected() {
        String body = "000201" + "010211" + "5303360" + "5802ID" + "5906Toko A" + "5906Toko B" + "6005Medan" + "6304";
        String raw = body + Crc16.hex(body);
        QrisException e = assertThrows(QrisException.class, () -> QrisPayload.parse(raw));
        assertTrue(e.getMessage().contains("dua kali"));
    }

    @Test
    void amountWithZeroCentsIsAccepted() {
        assertEquals(15_000L, QrisPayload.parseAmount("15000.00"));
        assertThrows(QrisException.class, () -> QrisPayload.parseAmount("15000.50"));
        assertThrows(QrisException.class, () -> QrisPayload.parseAmount("0"));
        assertThrows(QrisException.class, () -> QrisPayload.parseAmount("-5"));
    }

    @Test
    void missingCrcIsRejected() {
        assertThrows(QrisException.class, () -> QrisPayload.parse("000201010211"));
        assertThrows(QrisException.class, () -> QrisPayload.parse("   "));
    }

    @Test
    void merchantFieldsAreBounded() {
        assertThrows(QrisException.class, () -> new Merchant("M1", "Nama yang jauh lebih panjang dari 25", "Kota", "1",
                "5499", "N", "9360081200000000017"));
        assertThrows(QrisException.class, () -> new Merchant("M1", "Toko", "Kota", "1", "54", "N", "9360081200000000017"));
    }

    @Test
    void nonPositiveAmountCannotBeBuilt() {
        assertThrows(QrisException.class, () -> QrisPayload.build(WARUNG, 0L, "INV"));
    }
}
