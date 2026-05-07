package net.server.chat.irc;

public interface IrcSender {
    /** Returns true if the line was queued; false if dropped (queue full / not connected). */
    boolean enqueue(String rawIrcLine);

    /** Current bot nick, used for echo-loop suppression. */
    String currentNick();
}
