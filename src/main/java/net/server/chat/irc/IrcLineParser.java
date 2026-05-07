package net.server.chat.irc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IrcLineParser {

    private IrcLineParser() {}

    public static Optional<IrcMessage> parse(String raw) {
        if (raw == null) return Optional.empty();
        String line = raw.strip();
        if (line.isEmpty()) return Optional.empty();

        String prefix = "";
        if (line.startsWith(":")) {
            int sp = line.indexOf(' ');
            if (sp <= 1) return Optional.empty();
            prefix = line.substring(1, sp);
            line = line.substring(sp + 1).stripLeading();
            if (line.isEmpty()) return Optional.empty();
        }

        String trailing = "";
        int trailingIdx = line.indexOf(" :");
        if (trailingIdx >= 0) {
            trailing = line.substring(trailingIdx + 2);
            line = line.substring(0, trailingIdx);
        }

        String[] tokens = line.split(" +");
        if (tokens.length == 0 || tokens[0].isEmpty()) return Optional.empty();
        String command = tokens[0];
        List<String> params = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) params.add(tokens[i]);

        String nick = nickFromPrefix(prefix);
        return Optional.of(new IrcMessage(prefix, nick, command, List.copyOf(params), trailing));
    }

    private static String nickFromPrefix(String prefix) {
        if (prefix.isEmpty()) return "";
        int bang = prefix.indexOf('!');
        if (bang < 0) {
            int dot = prefix.indexOf('.');
            if (dot >= 0) return "";   // looks like a server name
            return prefix;
        }
        return prefix.substring(0, bang);
    }
}
