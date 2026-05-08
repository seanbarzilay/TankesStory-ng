package net.server.chat.telegram;

import net.packet.Packet;

@FunctionalInterface
public interface WorldBroadcaster {
    void broadcast(int worldId, Packet packet);
}
