package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OutstandingQuestionTrackerTest {

    @Test
    void start_returnsAscendingIds() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        int a = tr.start(0, 1, "Alice");
        int b = tr.start(0, 1, "Alice");
        int c = tr.start(1, 2, "Bob");
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void claim_validId_returnsEntryOnceThenEmpty() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        int id = tr.start(2, 7, "Carol");

        Optional<OutstandingQuestionTracker.Entry> first = tr.claim(id);
        assertTrue(first.isPresent());
        assertEquals(2, first.get().worldId());
        assertEquals(7, first.get().charId());
        assertEquals("Carol", first.get().charName());

        Optional<OutstandingQuestionTracker.Entry> second = tr.claim(id);
        assertTrue(second.isEmpty());
    }

    @Test
    void claim_unknownId_returnsEmpty() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        assertTrue(tr.claim(999).isEmpty());
    }

    @Test
    void claim_expiredId_returnsEmpty() {
        ManualClock clock = new ManualClock(0);
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofSeconds(60), clock);
        int id = tr.start(0, 1, "Alice");
        clock.advance(60_001);
        assertTrue(tr.claim(id).isEmpty());
    }

    @Test
    void sweep_removesExpiredEntriesOnRead() {
        ManualClock clock = new ManualClock(0);
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofSeconds(60), clock);
        int oldId = tr.start(0, 1, "Old");
        clock.advance(120_000);
        int newId = tr.start(0, 2, "New");

        tr.claim(newId);
        assertEquals(0, tr.size(), "sweep should have removed the expired Old entry");
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static final class ManualClock extends Clock {
        private final AtomicLong now;
        ManualClock(long startMillis) { this.now = new AtomicLong(startMillis); }
        void advance(long ms) { now.addAndGet(ms); }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { throw new UnsupportedOperationException(); }
    }
}
