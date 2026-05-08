package net.server.chat.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsBurstUpToBucketCapacity() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(6, clock);
        for (int i = 0; i < 6; i++) assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void refillsOverTime() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(6, clock);
        for (int i = 0; i < 6; i++) rl.tryAcquire(42);
        assertFalse(rl.tryAcquire(42));
        clock.advance(10_000);
        assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void perCharacterIsolation() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(1, clock);
        assertTrue(rl.tryAcquire(1));
        assertFalse(rl.tryAcquire(1));
        assertTrue(rl.tryAcquire(2));
    }

    private static final class ManualClock extends Clock {
        private final AtomicLong nowMillis;
        ManualClock(long startMillis) { this.nowMillis = new AtomicLong(startMillis); }
        void advance(long delta) { nowMillis.addAndGet(delta); }
        @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis.get()); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { throw new UnsupportedOperationException(); }
    }
}
