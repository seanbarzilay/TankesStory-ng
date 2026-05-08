package net.server.chat.irc;

import net.packet.Packet;

public interface PlayerSender {
    /**
     * @return true if the player was found online in that world and the packet
     *         was enqueued; false otherwise (caller drops the message).
     */
    boolean send(int worldId, int charId, Packet packet);
}
