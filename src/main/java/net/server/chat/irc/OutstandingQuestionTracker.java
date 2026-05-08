package net.server.chat.irc;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class OutstandingQuestionTracker {

    public record Entry(int worldId, int charId, String charName, long expiresAtMs) {}

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public OutstandingQuestionTracker(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public int start(int worldId, int charId, String charName) {
        long expiresAt = clock.millis() + ttl.toMillis();
        int id = nextId.getAndIncrement();
        entries.put(id, new Entry(worldId, charId, charName, expiresAt));
        sweep();
        return id;
    }

    public Optional<Entry> claim(int id) {
        sweep();
        Entry e = entries.remove(id);
        if (e == null) return Optional.empty();
        if (e.expiresAtMs() <= clock.millis()) return Optional.empty();
        return Optional.of(e);
    }

    public int size() { return entries.size(); }

    private void sweep() {
        long now = clock.millis();
        Iterator<ConcurrentHashMap.Entry<Integer, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            ConcurrentHashMap.Entry<Integer, Entry> kv = it.next();
            if (kv.getValue().expiresAtMs() <= now) it.remove();
        }
    }
}
