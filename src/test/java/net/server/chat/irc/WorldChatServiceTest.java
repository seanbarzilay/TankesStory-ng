package net.server.chat.irc;

import org.junit.jupiter.api.Test;
import tools.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatServiceTest {

    @Test
    void send_fansOutToBothLocalAndIrc() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(0, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
        assertEquals(1, sender.lines.size());
        assertEquals("PRIVMSG #cosmic-scania :Alice hi", sender.lines.get(0));
    }

    @Test
    void send_unmappedWorld_skipsIrcButStillSelfLoops() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(99, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.lines.size());
    }

    @Test
    void deliverFromIrc_broadcastsLightblueServerNoticeToWorld() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromIrc(0, "ircnick", "hello");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void deliverFromIrc_dropsEchoFromOwnNick() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromIrc(0, "Cosmic-Bridge", "loop?");

        assertEquals(0, bc.broadcasts.size());
    }

    @Test
    void deliverFromIrc_stripsControlCharsAndTruncates() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 5);

        svc.deliverFromIrc(0, "n", "ab\u0001cdef\u0002gh");
        // control chars stripped → "abcdefgh", truncated to 5 → "abcde…"
        assertEquals(1, bc.broadcasts.size());
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
        @Override public void broadcast(int worldId, Packet p) {
            broadcasts.add(new Bcast(worldId, p));
        }
    }

    record Bcast(int worldId, Packet packet) {}
}
