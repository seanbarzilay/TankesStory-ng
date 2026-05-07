package net.server.chat.irc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrcConnectionTest {

    private FakeIrcServer fake;
    private IrcConnection conn;

    @AfterEach
    void teardown() throws Exception {
        if (conn != null) conn.stop(2000);
        if (fake != null) fake.close();
    }

    @Test
    void onConnect_sendsNickAndUserAndJoinsAllChannels() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a", "#b"));

        conn.start();

        assertEquals("NICK cosmic-bridge", takeNonPing());
        assertEquals("USER cosmic 0 * :Cosmic Test", takeNonPing());
        // Many real servers wait for 001 before JOIN; for the test we send 001 to unblock JOIN.
        fake.send(":server 001 cosmic-bridge :Welcome");
        assertEquals("JOIN #a", takeNonPing());
        assertEquals("JOIN #b", takeNonPing());
    }

    @Test
    void incomingPing_isAnsweredWithPong() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a"));
        conn.start();
        drainRegistration();

        fake.send("PING :servername");
        // takeNonPing filters PONG, so use takeLine directly here
        assertEquals("PONG :servername", fake.takeLine(1500));
    }

    @Test
    void incomingPrivmsg_invokesListener() throws Exception {
        fake = new FakeIrcServer();
        java.util.concurrent.atomic.AtomicReference<IrcMessage> got = new java.util.concurrent.atomic.AtomicReference<>();
        conn = new IrcConnection.Builder()
                .host("127.0.0.1").port(fake.port()).tls(false)
                .nick("cosmic-bridge").user("cosmic").realname("Cosmic Test")
                .channels(List.of("#a")).queueMax(1000)
                .backoffSeconds(List.of(0)).onMessage(got::set).build();
        conn.start();
        drainRegistration();

        fake.send(":nick!u@h PRIVMSG #a :hello");
        // Spin until the listener received the message
        long deadline = System.currentTimeMillis() + 1500;
        while (got.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertNotNull(got.get());
        assertEquals("PRIVMSG", got.get().command());
    }

    @Test
    void enqueueWritesToSocket() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a"));
        conn.start();
        drainRegistration();

        assertTrue(conn.enqueue("PRIVMSG #a :ping from us"));
        assertEquals("PRIVMSG #a :ping from us", takeNonPing());
    }

    private IrcConnection newConnection(int port, List<String> channels) {
        return new IrcConnection.Builder()
                .host("127.0.0.1").port(port).tls(false)
                .nick("cosmic-bridge").user("cosmic").realname("Cosmic Test")
                .channels(channels).queueMax(1000)
                .backoffSeconds(List.of(0)).onMessage(m -> {})
                .build();
    }

    private String takeNonPing() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String line = fake.takeLine(1500);
            if (line == null) return null;
            if (line.startsWith("PING ") || line.startsWith("PONG ")) continue;
            return line;
        }
        return null;
    }

    private void drainRegistration() throws Exception {
        // NICK and USER lines
        fake.takeLine(1500);
        fake.takeLine(1500);
        fake.send(":server 001 cosmic-bridge :Welcome");
        // JOIN lines
        fake.takeLine(1500);
    }
}
