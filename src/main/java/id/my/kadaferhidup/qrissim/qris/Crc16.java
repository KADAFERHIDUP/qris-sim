package id.my.kadaferhidup.qrissim.qris;

import java.nio.charset.StandardCharsets;

/**
 * CRC-16/CCITT-FALSE, the checksum QRIS (EMVCo MPM) puts in tag 63.
 * Polynomial 0x1021, initial value 0xFFFF, no reflection, no final XOR.
 */
public final class Crc16 {

    private Crc16() {
    }

    public static int compute(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    /** Four upper-case hex digits, the way tag 63 carries them. */
    public static String hex(String data) {
        return String.format("%04X", compute(data));
    }
}
