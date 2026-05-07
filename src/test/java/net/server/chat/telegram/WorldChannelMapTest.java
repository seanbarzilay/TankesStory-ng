package net.server.chat.telegram;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChannelMapTest {

    @Test
    void resolvesWorldToChatAndBack() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, -1001234567890L,
                1, -1001234567891L
        ));
        assertEquals(-1001234567890L, map.chatId(0).orElseThrow());
        assertEquals(1, map.worldFor(-1001234567891L).orElseThrow());
    }

    @Test
    void unmappedReturnsEmpty() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        assertTrue(map.chatId(99).isEmpty());
        assertTrue(map.worldFor(-9999L).isEmpty());
    }

    @Test
    void allChats_returnsAllRegistered() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, -1001234567890L,
                1, -1001234567891L
        ));
        assertEquals(2, map.allChats().size());
        assertTrue(map.allChats().contains(-1001234567890L));
        assertTrue(map.allChats().contains(-1001234567891L));
    }
}
