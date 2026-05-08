package net.server.chat.irc;

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

class WorldChatServiceTest {

    @Test
    void send_localBroadcastIsUntagged_outboundIrcCarriesTag() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(0, 4, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(1, sender.lines.size());
        assertTrue(sender.lines.get(0).contains("[#"), "expected [#N] tag in: " + sender.lines.get(0));
        assertTrue(sender.lines.get(0).endsWith("hi"));
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void send_unmappedWorld_skipsIrc_andDoesNotAllocateTag() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(99, 4, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.lines.size());
        assertEquals(0, tracker.size(), "no IRC channel → no tag should be allocated");
    }

    @Test
    void deliverFromIrc_taggedAndAskerOnline_popupOnly_noWorldBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        ps.online.add(new PlayerKey(0, 4));
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(0, 4, "Alice", "what mobs drop the red whip?");
        svc.deliverFromIrc(0, "ircbot", "[#1] Wraith, Ginseng Jar");

        assertEquals(1, bc.broadcasts.size(), "tagged answer must NOT broadcast to world");
        assertEquals(1, ps.calls.size());
        assertEquals(0, ps.calls.get(0).worldId);
        assertEquals(4, ps.calls.get(0).charId);
    }

    @Test
    void deliverFromIrc_taggedButTrackerMiss_fallsThroughToBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.deliverFromIrc(0, "ircbot", "[#99] late answer");

        assertEquals(1, bc.broadcasts.size(), "unmatched tag → fall through to world broadcast");
        assertEquals(0, ps.calls.size(), "no popup");
    }

    @Test
    void deliverFromIrc_taggedAndAskerOffline_dropsPopup_noBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(0, 4, "Alice", "q");
        svc.deliverFromIrc(0, "ircbot", "[#1] late answer");

        assertEquals(1, bc.broadcasts.size(), "no follow-up broadcast for offline asker");
        assertEquals(1, ps.calls.size(), "send was attempted");
    }

    @Test
    void deliverFromIrc_untagged_broadcastsAsToday() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.deliverFromIrc(0, "ircuser", "general chatter");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, ps.calls.size());
    }

    @Test
    void deliverFromIrc_echoFromOwnNick_dropped() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.deliverFromIrc(0, "Cosmic-Bridge", "loop?");

        assertEquals(0, bc.broadcasts.size());
        assertEquals(0, ps.calls.size());
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    static final class FakeSender implements IrcSender {
        final List<String> lines = new ArrayList<>();
        final String nick;
        FakeSender(String nick) { this.nick = nick; }
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return nick; }
    }

    static final class FakeBroadcaster implements WorldBroadcaster {
        final List<Bcast> broadcasts = new ArrayList<>();
        @Override public void broadcast(int worldId, Packet p) { broadcasts.add(new Bcast(worldId, p)); }
    }

    record Bcast(int worldId, Packet packet) {}

    record PlayerKey(int worldId, int charId) {}

    static final class PSendCall {
        final int worldId;
        final int charId;
        PSendCall(int worldId, int charId) { this.worldId = worldId; this.charId = charId; }
    }

    static final class FakePlayerSender implements PlayerSender {
        final java.util.Set<PlayerKey> online = new java.util.HashSet<>();
        final List<PSendCall> calls = new ArrayList<>();
        @Override public boolean send(int worldId, int charId, Packet packet) {
            calls.add(new PSendCall(worldId, charId));
            return online.contains(new PlayerKey(worldId, charId));
        }
    }
}
