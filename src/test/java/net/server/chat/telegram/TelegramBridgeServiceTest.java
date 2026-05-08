package net.server.chat.telegram;

import client.command.CommandsExecutor;
import config.TelegramConfigYaml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramBridgeServiceTest {

    private FakeTelegramApi fake;
    private TelegramBridgeService svc;

    @AfterEach
    void teardown() throws Exception {
        if (svc != null) svc.stop(2000);
        if (fake != null) fake.close();
        TelegramBridgeService.clearInstance();
        CommandsExecutor.getInstance().unregisterCommand("world");
    }

    @Test
    void start_thenSendRoutesToTelegram() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));

        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());

        svc.worldChat().send(0, "Alice", "hi");

        String body = fake.takeSentMessage(2000);
        assertNotNull(body, "no sendMessage POST received");
        assertTrue(body.contains("Alice"), "body=" + body);
        assertTrue(body.contains("-1001"), "body=" + body);
        assertEquals(1, broadcasts.size());
    }

    @Test
    void inboundTextDeliveredAsLocalBroadcast() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));
        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());

        fake.injectUpdate(
                "{\"update_id\":100," +
                " \"message\":{\"message_id\":1,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\",\"username\":\"alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"text\":\"hi back\"}}");

        long deadline = System.currentTimeMillis() + 4000;
        while (broadcasts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, broadcasts.size(), "no inbound broadcast");
        assertEquals(0, broadcasts.get(0)[0]);
    }

    @Test
    void start_registersWorldCommand_andStopUnregistersIt() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));
        WorldBroadcaster bc = (w, p) -> {};

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());
        assertTrue(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));

        svc.stop(2000);
        svc = null;
        assertFalse(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));
    }

    private TelegramConfigYaml yaml(String apiUrl) {
        TelegramConfigYaml y = new TelegramConfigYaml();
        y.enabled = true;
        y.bot_token = "test:token";
        y.api_url = apiUrl;
        y.poll_timeout_seconds = 1;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.chats = Map.of(0, -1001L);
        return y;
    }
}
