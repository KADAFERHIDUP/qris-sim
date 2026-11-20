package id.my.kadaferhidup.qrissim.qris;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Crc16Test {

    @Test
    void standardCheckValue() {
        // CRC-16/CCITT-FALSE of "123456789" is 0x29B1 in every reference table.
        assertEquals(0x29B1, Crc16.compute("123456789"));
    }

    @Test
    void hexIsFourUpperCaseDigits() {
        assertEquals("29B1", Crc16.hex("123456789"));
        assertEquals("FFFF", Crc16.hex(""));
    }
}
