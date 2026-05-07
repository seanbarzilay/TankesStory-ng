package net.server.chat.irc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WorldChannelMap {

    private final Map<Integer, String> worldToChannel;
    private final Map<String, Integer> channelToWorld;

    private WorldChannelMap(Map<Integer, String> worldToChannel,
                            Map<String, Integer> channelToWorld) {
        this.worldToChannel = worldToChannel;
        this.channelToWorld = channelToWorld;
    }

    public static WorldChannelMap of(Map<Integer, String> raw) {
        Map<Integer, String> w = new HashMap<>();
        Map<String, Integer> c = new HashMap<>();
        for (Map.Entry<Integer, String> e : raw.entrySet()) {
            w.put(e.getKey(), e.getValue());
            c.put(e.getValue().toLowerCase(Locale.ROOT), e.getKey());
        }
        return new WorldChannelMap(Map.copyOf(w), Map.copyOf(c));
    }

    public Optional<String> channel(int worldId) {
        return Optional.ofNullable(worldToChannel.get(worldId));
    }

    public Optional<Integer> world(String channel) {
        return Optional.ofNullable(channelToWorld.get(channel.toLowerCase(Locale.ROOT)));
    }

    public Collection<String> allChannels() {
        return worldToChannel.values();
    }
}
