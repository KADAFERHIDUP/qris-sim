package id.my.kadaferhidup.qrissim.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsTheBurstThenWaits() {
        TestClock clock = new TestClock();
        RateLimiter rl = new RateLimiter(clock, 60, 5);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, rl.acquire("a"));
        }
        assertEquals(1, rl.acquire("a"), "satu token per detik, tunggu 1 detik");
    }

    @Test
    void refillsOverTime() {
        TestClock clock = new TestClock();
        RateLimiter rl = new RateLimiter(clock, 60, 2);
        rl.acquire("a");
        rl.acquire("a");
        assertTrue(rl.acquire("a") > 0);
        clock.now = clock.now.plus(Duration.ofSeconds(2));
        assertEquals(0, rl.acquire("a"));
        assertEquals(0, rl.acquire("a"));
        assertTrue(rl.acquire("a") > 0);
    }

    @Test
    void clientsAreCountedSeparately() {
        RateLimiter rl = new RateLimiter(new TestClock(), 60, 1);
        assertEquals(0, rl.acquire("a"));
        assertEquals(0, rl.acquire("b"));
        assertEquals(2, rl.clients());
    }

    @Test
    void refusesZeroLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(new TestClock(), 0, 1));
    }
}
