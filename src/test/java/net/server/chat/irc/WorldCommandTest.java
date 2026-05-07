package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldCommandTest {

    @Test
    void deliver_routesToService_andRespectsRateLimit() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, rec, rec, 200);
        RateLimiter rl = new RateLimiter(2, fixedClock());

        IrcWorldCommandDelivery.deliver(svc, rl, 0, 42, "Alice", "hi");
        IrcWorldCommandDelivery.deliver(svc, rl, 0, 42, "Alice", "hi2");
        IrcWorldCommandDelivery.deliver(svc, rl, 0, 42, "Alice", "blocked");

        assertEquals(2, rec.broadcasts.size(), "third call should be rate-limited");
    }

    @Test
    void deliver_emptyText_droppedSilently() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, rec, rec, 200);
        RateLimiter rl = new RateLimiter(10, fixedClock());

        IrcWorldCommandDelivery.deliver(svc, rl, 0, 42, "Alice", "   ");

        assertEquals(0, rec.broadcasts.size());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC);
    }

    /** Helper to test IRC delivery mechanism independently of shared WorldCommand */
    static final class IrcWorldCommandDelivery {
        static void deliver(WorldChatService svc, RateLimiter rl,
                           int worldId, int charId, String charName, String text) {
            if (text == null || text.strip().isEmpty()) return;
            if (!rl.tryAcquire(charId)) return;
            svc.send(worldId, charName, text);
        }
    }

    static final class FakeRecorder implements IrcSender, WorldBroadcaster {
        final List<String> lines = new ArrayList<>();
        final List<Integer> broadcasts = new ArrayList<>();
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return "bot"; }
        @Override public void broadcast(int w, net.packet.Packet p) { broadcasts.add(w); }
    }
}
