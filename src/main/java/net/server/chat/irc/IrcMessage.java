package net.server.chat.irc;

import java.util.List;

public record IrcMessage(String prefix, String nick, String command,
                         List<String> params, String trailing) {
}
