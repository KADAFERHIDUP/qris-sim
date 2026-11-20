package id.my.kadaferhidup.qrissim.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies so a merchant can prove a call came from the simulator.
 *
 * <p>Header: {@code X-QrisSim-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>}, where the
 * HMAC covers {@code "<t>.<raw body>"}. Putting the timestamp under the signature lets the
 * receiver reject old copies replayed by someone who captured one.
 */
public final class WebhookSigner {

    public static final String HEADER = "X-QrisSim-Signature";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSigner() {
    }

    public static String sign(String secret, Instant at, String body) {
        long t = at.getEpochSecond();
        return "t=" + t + ",v1=" + hmac(secret, t + "." + body);
    }

    /** Result of checking a received call. */
    public enum Verdict {
        VALID, MALFORMED, BAD_SIGNATURE, TOO_OLD
    }

    public static Verdict verify(String secret, String header, String body, Instant now, Duration tolerance) {
        if (header == null) {
            return Verdict.MALFORMED;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                return Verdict.MALFORMED;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return Verdict.MALFORMED;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return Verdict.MALFORMED;
        }
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, v1.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.BAD_SIGNATURE;
        }
        if (Math.abs(now.getEpochSecond() - t) > tolerance.toSeconds()) {
            return Verdict.TOO_OLD;
        }
        return Verdict.VALID;
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 tidak tersedia", e);
        }
    }
}
