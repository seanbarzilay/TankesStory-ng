package net.server.chat.irc;

import tools.PacketCreator;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;

    private final WorldChannelMap channels;
    private final IrcSender sender;
    private final WorldBroadcaster broadcaster;
    private final int maxLength;

    public WorldChatService(WorldChannelMap channels, IrcSender sender,
                            WorldBroadcaster broadcaster, int maxLength) {
        this.channels = channels;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.maxLength = maxLength;
    }

    public void send(int worldId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        channels.channel(worldId).ifPresent(chan ->
                sender.enqueue("PRIVMSG " + chan + " :" + charName + " " + clean));
    }

    public void deliverFromIrc(int worldId, String nick, String text) {
        if (sender.currentNick() != null && nick.equalsIgnoreCase(sender.currentNick())) return;
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, "[IRC]" + nick + ": " + clean));
    }

    private String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20) sb.append(c);
        }
        String stripped = sb.toString().strip();
        if (stripped.length() > maxLength) {
            return stripped.substring(0, maxLength) + "…";
        }
        return stripped;
    }
}
