package net.server.chat.irc;

import client.command.commands.gm0.WorldCommand;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
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
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        FakePlayerSender ps = new FakePlayerSender();
        WorldChatService svc = new WorldChatService(map, rec, rec, tracker, ps, 200);
        RateLimiter rl = new RateLimiter(2, fixed(0));

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi2");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "blocked");

        assertEquals(2, rec.broadcasts.size(), "third call should be rate-limited");
    }

    @Test
    void deliver_emptyText_droppedSilently() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        FakePlayerSender ps = new FakePlayerSender();
        WorldChatService svc = new WorldChatService(map, rec, rec, tracker, ps, 200);
        RateLimiter rl = new RateLimiter(10, fixed(0));

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "   ");

        assertEquals(0, rec.broadcasts.size());
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    static final class FakeRecorder implements IrcSender, WorldBroadcaster {
        final List<String> lines = new ArrayList<>();
        final List<Integer> broadcasts = new ArrayList<>();
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return "bot"; }
        @Override public void broadcast(int w, Packet p) { broadcasts.add(w); }
    }

    static final class FakePlayerSender implements PlayerSender {
        @Override public boolean send(int worldId, int charId, Packet packet) { return false; }
    }
}
