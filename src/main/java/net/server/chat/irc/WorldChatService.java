package net.server.chat.irc;

import tools.PacketCreator;

import java.util.Optional;
import java.util.OptionalInt;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;
    private static final int POPUP_NOTICE = 1;

    private final WorldChannelMap channels;
    private final IrcSender sender;
    private final WorldBroadcaster broadcaster;
    private final OutstandingQuestionTracker tracker;
    private final PlayerSender playerSender;
    private final int maxLength;

    public WorldChatService(WorldChannelMap channels, IrcSender sender,
                            WorldBroadcaster broadcaster,
                            OutstandingQuestionTracker tracker,
                            PlayerSender playerSender,
                            int maxLength) {
        this.channels = channels;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.tracker = tracker;
        this.playerSender = playerSender;
        this.maxLength = maxLength;
    }

    public void send(int worldId, int charId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        // Local self-loop: untagged, exactly as before.
        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        // Outbound to IRC: inject [#N] correlation tag so a reply can be routed back.
        channels.channel(worldId).ifPresent(chan -> {
            int tagId = tracker.start(worldId, charId, charName);
            sender.enqueue("PRIVMSG " + chan + " :" + charName + " " + QuestionTag.marker(tagId) + " " + clean);
        });
    }

    public void deliverFromIrc(int worldId, String nick, String text) {
        if (sender.currentNick() != null && nick.equalsIgnoreCase(sender.currentNick())) return;
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        // Tag-routing fast path: tagged + tracker hit + asker online → directed popup.
        OptionalInt tagId = QuestionTag.parseFirst(clean);
        if (tagId.isPresent()) {
            Optional<OutstandingQuestionTracker.Entry> entry = tracker.claim(tagId.getAsInt());
            if (entry.isPresent()) {
                String stripped = QuestionTag.strip(clean);
                playerSender.send(entry.get().worldId(), entry.get().charId(),
                        PacketCreator.serverNotice(POPUP_NOTICE, stripped));
                // Whether or not delivered, we DO NOT fall through to broadcast —
                // the answer was directed and we already claimed the tag.
                return;
            }
            // Tag present but tracker has no entry (expired / restart) — fall through to broadcast.
        }

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
