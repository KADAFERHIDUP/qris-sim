package id.my.kadaferhidup.qrissim.qris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TlvTest {

    @Test
    void encodesLengthAsTwoDigits() {
        assertEquals("5303360", new Tlv("53", "360").encode());
        assertEquals("5912Warung Ahmad", new Tlv("59", "Warung Ahmad").encode());
    }

    @Test
    void parsesWhatItEncodes() {
        List<Tlv> in = List.of(new Tlv("00", "01"), new Tlv("59", "Toko A"), new Tlv("60", "Lhokseumawe"));
        assertEquals(in, Tlv.parseAll(Tlv.encodeAll(in)));
    }

    @Test
    void rejectsTruncatedValue() {
        assertThrows(QrisException.class, () -> Tlv.parseAll("5910Toko"));
    }

    @Test
    void rejectsNonNumericLength() {
        assertThrows(QrisException.class, () -> Tlv.parseAll("59XXToko"));
    }

    @Test
    void rejectsDanglingBytes() {
        assertThrows(QrisException.class, () -> Tlv.parseAll("000201" + "5"));
    }
}
