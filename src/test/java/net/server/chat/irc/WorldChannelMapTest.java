package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorldChannelMapTest {

    @Test
    void resolvesWorldToChannelAndBack() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, "#cosmic-scania",
                1, "#cosmic-bera"
        ));
        assertEquals("#cosmic-scania", map.channel(0).orElseThrow());
        assertEquals(1, map.world("#cosmic-bera").orElseThrow());
    }

    @Test
    void channelLookupIsCaseInsensitive() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#Cosmic-Scania"));
        assertEquals(0, map.world("#cosmic-scania").orElseThrow());
        assertEquals(0, map.world("#COSMIC-SCANIA").orElseThrow());
    }

    @Test
    void unmappedReturnsEmpty() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        assertTrue(map.channel(99).isEmpty());
        assertTrue(map.world("#nope").isEmpty());
    }

    @Test
    void allChannels_returnsAllRegisteredChannels() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a", 1, "#b"));
        assertEquals(2, map.allChannels().size());
        assertTrue(map.allChannels().contains("#a"));
        assertTrue(map.allChannels().contains("#b"));
    }
}
