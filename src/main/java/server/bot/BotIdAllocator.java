package server.bot;

import java.util.concurrent.atomic.AtomicInteger;

public class BotIdAllocator {

    public static final int START = -1_000_000;

    private final AtomicInteger next;

    public BotIdAllocator() {
        this(START);
    }

    public BotIdAllocator(int seed) {
        this.next = new AtomicInteger(seed + 1);
    }

    public int next() {
        return next.decrementAndGet();
    }
}
