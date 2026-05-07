package net.server.chat.telegram;

import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatServiceTest {

    @Test
    void send_fansOutToBothLocalAndTelegram() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@cosmic_bridge_bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(0, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
        assertEquals(1, sender.calls.size());
        assertEquals(-1001234567890L, sender.calls.get(0).chatId);
        assertEquals("Alice hi", sender.calls.get(0).text);
    }

    @Test
    void send_unmappedWorld_skipsTelegramButStillSelfLoops() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(99, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.calls.size());
    }

    @Test
    void deliverFromTelegram_broadcastsLightblueServerNoticeToWorld() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromTelegram(0, "@friend", "hello");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void deliverFromTelegram_emptyAfterSanitize_dropped() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromTelegram(0, "@friend", "   ");
        svc.deliverFromTelegram(0, "@friend", "");
        svc.deliverFromTelegram(0, "@friend", null);

        assertEquals(0, bc.broadcasts.size());
    }

    @Test
    void deliverFromTelegram_stripsControlCharsAndTruncates() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 5);

        svc.deliverFromTelegram(0, "n", "ab\u0001cdef\u0002gh");
        // strip control chars → "abcdefgh", truncate to 5 → "abcde…"
        assertEquals(1, bc.broadcasts.size());
    }

    static final class SendCall {
        final long chatId;
        final String text;
        SendCall(long chatId, String text) { this.chatId = chatId; this.text = text; }
    }

    static final class FakeSender implements TelegramSender {
        final List<SendCall> calls = new ArrayList<>();
        final String username;
        FakeSender(String username) { this.username = username; }
        @Override public void sendToChat(long chatId, String text) {
            calls.add(new SendCall(chatId, text));
        }
        @Override public String currentBotUsername() { return username; }
    }

    static final class FakeBroadcaster implements WorldBroadcaster {
        final List<Bcast> broadcasts = new ArrayList<>();
        @Override public void broadcast(int worldId, Packet p) {
            broadcasts.add(new Bcast(worldId, p));
        }
    }

    record Bcast(int worldId, Packet packet) {}
}
