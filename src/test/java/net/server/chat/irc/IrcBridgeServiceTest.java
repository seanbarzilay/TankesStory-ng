package net.server.chat.irc;

import client.command.CommandsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrcBridgeServiceTest {

    private FakeIrcServer fake;
    private IrcBridgeService svc;

    @AfterEach
    void teardown() throws Exception {
        if (svc != null) svc.stop(2000);
        if (fake != null) fake.close();
        IrcBridgeService.clearInstance();
        CommandsExecutor.getInstance().unregisterCommand("world");
    }

    @Test
    void start_dialsAndJoinsChannels_thenSendRoutesGameTextToIrc() throws Exception {
        fake = new FakeIrcServer();
        IrcConfig cfg = IrcConfig.from(yamlFor(fake.port()));

        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = IrcBridgeService.start(cfg, bc, Clock.systemUTC());

        // bridge has dialed, sent NICK + USER; we send 001 to unblock JOIN
        fake.takeLine(2000);  // NICK
        fake.takeLine(2000);  // USER
        fake.send(":server 001 " + cfg.nick() + " :Welcome");
        String join = fake.takeLine(2000);
        assertEquals("JOIN #cosmic-test", join);

        // game player @world hi
        svc.worldChat().send(0, "Alice", "hi");
        String line = fake.takeLine(2000);
        assertEquals("PRIVMSG #cosmic-test :Alice hi", line);
        assertEquals(1, broadcasts.size());

        // inbound from IRC
        fake.send(":someone!u@h PRIVMSG #cosmic-test :hello back");
        long deadline = System.currentTimeMillis() + 1500;
        while (broadcasts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(2, broadcasts.size());
    }

    @Test
    void start_registersWorldCommand_andStopUnregistersIt() throws Exception {
        fake = new FakeIrcServer();
        IrcConfig cfg = IrcConfig.from(yamlFor(fake.port()));
        WorldBroadcaster bc = (w, p) -> {};

        svc = IrcBridgeService.start(cfg, bc, Clock.systemUTC());
        assertTrue(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));

        svc.stop(2000);
        svc = null;
        assertFalse(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));
    }

    private config.IrcConfigYaml yamlFor(int port) {
        config.IrcConfigYaml y = new config.IrcConfigYaml();
        y.enabled = true;
        y.server = "127.0.0.1";
        y.port = port;
        y.tls = false;
        y.nick = "cosmic-bridge";
        y.user = "cosmic";
        y.realname = "Cosmic";
        y.password = "";
        y.allow_plaintext_password = false;
        y.channels = Map.of(0, "#cosmic-test");
        y.outbound_queue_max = 1000;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.reconnect_backoff_seconds = List.of(0);
        return y;
    }
}
