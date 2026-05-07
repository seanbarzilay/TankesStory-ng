package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IrcLineParserTest {

    @Test
    void privmsg_simple() {
        Optional<IrcMessage> msg = IrcLineParser.parse(":nick!u@h PRIVMSG #chan :hello world");
        assertTrue(msg.isPresent());
        IrcMessage m = msg.orElseThrow();
        assertEquals("nick", m.nick());
        assertEquals("PRIVMSG", m.command());
        assertEquals("#chan", m.params().get(0));
        assertEquals("hello world", m.trailing());
    }

    @Test
    void ping_noPrefix() {
        IrcMessage m = IrcLineParser.parse("PING :foo").orElseThrow();
        assertEquals("", m.nick());
        assertEquals("PING", m.command());
        assertEquals("foo", m.trailing());
    }

    @Test
    void numericReply_001() {
        IrcMessage m = IrcLineParser.parse(":server.example 001 cosmic-bridge :Welcome to the network").orElseThrow();
        assertEquals("001", m.command());
        assertEquals("cosmic-bridge", m.params().get(0));
        assertEquals("Welcome to the network", m.trailing());
    }

    @Test
    void empty_returnsEmpty() {
        assertTrue(IrcLineParser.parse("").isEmpty());
        assertTrue(IrcLineParser.parse("   ").isEmpty());
    }

    @Test
    void malformed_singleColon_returnsEmpty() {
        assertTrue(IrcLineParser.parse(":").isEmpty());
    }

    @Test
    void privmsg_noTrailing() {
        IrcMessage m = IrcLineParser.parse(":a!b@c PRIVMSG #chan").orElseThrow();
        assertEquals("PRIVMSG", m.command());
        assertEquals("", m.trailing());
    }

    @Test
    void nickFromPrefix_handlesUserHostMissing() {
        IrcMessage m = IrcLineParser.parse(":nick PRIVMSG #chan :hi").orElseThrow();
        assertEquals("nick", m.nick());
    }
}
