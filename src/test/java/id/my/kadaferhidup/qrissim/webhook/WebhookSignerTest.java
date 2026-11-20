package id.my.kadaferhidup.qrissim.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    static final String SECRET = "whsec_demo_1234567890";
    static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    static final String BODY = "{\"type\":\"invoice.paid\"}";

    @Test
    void knownHmacVector() {
        // RFC 4231 test case 2
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                WebhookSigner.hmac("Jefe", "what do ya want for nothing?"));
    }

    @Test
    void signatureVerifies() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertTrue(header.startsWith("t=" + NOW.getEpochSecond() + ",v1="));
        assertEquals(WebhookSigner.Verdict.VALID,
                WebhookSigner.verify(SECRET, header, BODY, NOW.plusSeconds(10), WebhookSigner.DEFAULT_TOLERANCE));
    }

    @Test
    void changedBodyFails() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertEquals(WebhookSigner.Verdict.BAD_SIGNATURE,
                WebhookSigner.verify(SECRET, header, BODY.replace("paid", "cancelled"), NOW, WebhookSigner.DEFAULT_TOLERANCE));
    }

    @Test
    void wrongSecretFails() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertEquals(WebhookSigner.Verdict.BAD_SIGNATURE,
                WebhookSigner.verify("whsec_lain_1234567890", header, BODY, NOW, WebhookSigner.DEFAULT_TOLERANCE));
    }

    @Test
    void replayedOldCallIsRejected() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertEquals(WebhookSigner.Verdict.TOO_OLD,
                WebhookSigner.verify(SECRET, header, BODY, NOW.plus(Duration.ofMinutes(6)), WebhookSigner.DEFAULT_TOLERANCE));
    }

    @Test
    void malformedHeaders() {
        for (String h : new String[] {null, "", "v1=abc", "t=abc,v1=x", "t=1"}) {
            assertEquals(WebhookSigner.Verdict.MALFORMED,
                    WebhookSigner.verify(SECRET, h, BODY, NOW, WebhookSigner.DEFAULT_TOLERANCE), String.valueOf(h));
        }
    }
}
