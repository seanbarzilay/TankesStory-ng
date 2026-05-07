package net.server.chat.irc;

import tools.Packet;

@FunctionalInterface
public interface WorldBroadcaster {
    void broadcast(int worldId, Packet packet);
}
