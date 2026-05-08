package net.server.chat.telegram;

import tools.PacketCreator;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;

    private final WorldChannelMap chats;
    private final TelegramSender sender;
    private final WorldBroadcaster broadcaster;
    private final int maxLength;

    public WorldChatService(WorldChannelMap chats, TelegramSender sender,
                            WorldBroadcaster broadcaster, int maxLength) {
        this.chats = chats;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.maxLength = maxLength;
    }

    public void send(int worldId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        chats.chatId(worldId).ifPresent(chatId ->
                sender.sendToChat(chatId, charName + " " + clean));
    }

    public void deliverFromTelegram(int worldId, String sender, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, "[TG]" + sender + ": " + clean));
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
