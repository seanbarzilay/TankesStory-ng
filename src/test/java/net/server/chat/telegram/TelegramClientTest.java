package net.server.chat.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramClientTest {

    private FakeTelegramApi fake;
    private TelegramClient client;

    @AfterEach
    void teardown() throws Exception {
        if (client != null) client.stop(2000);
        if (fake != null) fake.close();
    }

    @Test
    void inboundTextMessage_invokesListener() throws Exception {
        fake = new FakeTelegramApi();
        AtomicReference<TelegramClient.RawMessage> got = new AtomicReference<>();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(got::set)
                .build();
        client.start();

        fake.injectUpdate(
                "{\"update_id\":100," +
                " \"message\":{\"message_id\":1,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\",\"username\":\"alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"text\":\"hello world\"}}");

        long deadline = System.currentTimeMillis() + 4000;
        while (got.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertNotNull(got.get(), "listener never fired");
        assertEquals(-1001L, got.get().chatId());
        assertEquals("hello world", got.get().text());
        assertEquals("alice", got.get().fromUsername());
        assertEquals("Alice", got.get().fromFirstName());
    }

    @Test
    void sendToChat_postsToFakeApi() throws Exception {
        fake = new FakeTelegramApi();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(m -> {})
                .build();
        client.start();

        client.sendToChat(-1001L, "Alice hello");

        String body = fake.takeSentMessage(2000);
        assertNotNull(body, "no sendMessage POST received");
        assertTrue(body.contains("-1001"), "body=" + body);
        assertTrue(body.contains("Alice"), "body=" + body);
        assertTrue(body.contains("hello"), "body=" + body);
    }

    @Test
    void nonTextUpdate_dropped() throws Exception {
        fake = new FakeTelegramApi();
        AtomicReference<TelegramClient.RawMessage> got = new AtomicReference<>();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(got::set)
                .build();
        client.start();

        fake.injectUpdate(
                "{\"update_id\":101," +
                " \"message\":{\"message_id\":2,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"sticker\":{\"file_id\":\"x\",\"emoji\":\"🎉\"}}}");

        Thread.sleep(2500);
        assertNull(got.get(), "listener fired for a non-text update");
    }
}
