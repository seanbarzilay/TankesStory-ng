package net.server.chat.irc;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

    private final double tokensPerMs;
    private final double capacity;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        this.capacity = perMinute;
        this.tokensPerMs = perMinute / 60_000.0;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(int key) {
        long nowMs = clock.millis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowMs));
        long elapsed = nowMs - b.lastRefillMs;
        b.tokens = Math.min(capacity, b.tokens + elapsed * tokensPerMs);
        b.lastRefillMs = nowMs;
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return true;
        }
        return false;
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
        Bucket(double tokens, long lastRefillMs) {
            this.tokens = tokens;
            this.lastRefillMs = lastRefillMs;
        }
    }
}
