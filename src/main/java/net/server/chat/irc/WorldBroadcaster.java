package net.server.chat.irc;

import net.packet.Packet;

@FunctionalInterface
public interface WorldBroadcaster {
    void broadcast(int worldId, Packet packet);
}
