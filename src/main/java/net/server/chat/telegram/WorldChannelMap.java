package net.server.chat.telegram;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class WorldChannelMap {

    private final Map<Integer, Long> worldToChat;
    private final Map<Long, Integer> chatToWorld;

    private WorldChannelMap(Map<Integer, Long> worldToChat, Map<Long, Integer> chatToWorld) {
        this.worldToChat = worldToChat;
        this.chatToWorld = chatToWorld;
    }

    public static WorldChannelMap of(Map<Integer, Long> raw) {
        Map<Integer, Long> w = new HashMap<>();
        Map<Long, Integer> c = new HashMap<>();
        for (Map.Entry<Integer, Long> e : raw.entrySet()) {
            w.put(e.getKey(), e.getValue());
            c.put(e.getValue(), e.getKey());
        }
        return new WorldChannelMap(Map.copyOf(w), Map.copyOf(c));
    }

    public Optional<Long> chatId(int worldId) {
        return Optional.ofNullable(worldToChat.get(worldId));
    }

    public Optional<Integer> worldFor(long chatId) {
        return Optional.ofNullable(chatToWorld.get(chatId));
    }

    public Collection<Long> allChats() {
        return worldToChat.values();
    }
}
