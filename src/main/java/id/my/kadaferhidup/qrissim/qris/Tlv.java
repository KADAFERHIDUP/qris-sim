package id.my.kadaferhidup.qrissim.qris;

import java.util.ArrayList;
import java.util.List;

/**
 * One EMVCo data object: a two-digit tag, a two-digit decimal length and the value.
 */
public record Tlv(String tag, String value) {

    public Tlv {
        if (tag == null || !tag.matches("\\d{2}")) {
            throw new QrisException("Tag harus dua digit angka: " + tag);
        }
        if (value == null) {
            throw new QrisException("Nilai tag " + tag + " kosong");
        }
        if (value.length() > 99) {
            throw new QrisException("Nilai tag " + tag + " lebih dari 99 karakter");
        }
    }

    public String encode() {
        return tag + String.format("%02d", value.length()) + value;
    }

    public static String encodeAll(List<Tlv> objects) {
        StringBuilder sb = new StringBuilder();
        for (Tlv t : objects) {
            sb.append(t.encode());
        }
        return sb.toString();
    }

    /** Splits a string of concatenated data objects. Nothing is left over or the input is rejected. */
    public static List<Tlv> parseAll(String data) {
        List<Tlv> out = new ArrayList<>();
        int i = 0;
        while (i < data.length()) {
            if (i + 4 > data.length()) {
                throw new QrisException("Data terpotong di posisi " + i);
            }
            String tag = data.substring(i, i + 2);
            String len = data.substring(i + 2, i + 4);
            if (!tag.matches("\\d{2}") || !len.matches("\\d{2}")) {
                throw new QrisException("Tag atau panjang bukan angka di posisi " + i);
            }
            int n = Integer.parseInt(len);
            int start = i + 4;
            if (start + n > data.length()) {
                throw new QrisException("Tag " + tag + " meminta " + n + " karakter, sisa data kurang");
            }
            out.add(new Tlv(tag, data.substring(start, start + n)));
            i = start + n;
        }
        return out;
    }
}
