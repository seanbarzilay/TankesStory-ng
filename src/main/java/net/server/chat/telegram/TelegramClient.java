package net.server.chat.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TelegramClient implements TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    /** Decoded inbound message; the bridge service maps chatId -> worldId
     *  and turns this into a TelegramInbound for WorldChatService. */
    public record RawMessage(long chatId, String fromUsername, String fromFirstName,
                             String fromLastName, String text) {}

    private final TelegramBot bot;
    private final int pollTimeoutSeconds;
    private final Consumer<RawMessage> onMessage;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);
    private final AtomicLong lastWarnAtMs = new AtomicLong(0);
    private volatile String botUsername = "";
    private volatile Thread pollThread;

    private TelegramClient(Builder b) {
        TelegramBot.Builder bb = new TelegramBot.Builder(b.botToken);
        if (b.apiUrl != null && !b.apiUrl.isBlank()) {
            bb.apiUrl(b.apiUrl + "/bot");
        }
        this.bot = bb.build();
        this.pollTimeoutSeconds = b.pollTimeoutSeconds;
        this.onMessage = b.onMessage;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        pollThread = new Thread(this::pollLoop, "telegram-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void stop(long timeoutMs) {
        if (!running.compareAndSet(true, false)) return;
        if (pollThread != null) pollThread.interrupt();
        try {
            if (pollThread != null) pollThread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void sendToChat(long chatId, String text) {
        if (!running.get()) return;
        try {
            bot.execute(new SendMessage(chatId, text), new com.pengrad.telegrambot.Callback<>() {
                @Override public void onResponse(SendMessage req, SendResponse resp) {
                    if (!resp.isOk()) {
                        warnOnce("telegram sendMessage rejected: chat=" + chatId
                                + " code=" + resp.errorCode() + " desc=" + resp.description());
                    }
                }
                @Override public void onFailure(SendMessage req, IOException e) {
                    warnOnce("telegram sendMessage failed: chat=" + chatId + " err=" + e);
                }
            });
        } catch (Exception e) {
            log.warn("telegram sendMessage threw synchronously: {}", e.toString());
        }
    }

    @Override public String currentBotUsername() { return botUsername; }

    private void pollLoop() {
        while (running.get()) {
            try {
                GetUpdatesResponse resp = bot.execute(
                        new GetUpdates()
                                .offset((int) (lastUpdateId.get() + 1))
                                .timeout(pollTimeoutSeconds));
                if (resp == null || !resp.isOk() || resp.updates() == null) {
                    sleepBackoff(5000);
                    continue;
                }
                for (com.pengrad.telegrambot.model.Update u : resp.updates()) {
                    long uid = u.updateId();
                    if (uid > lastUpdateId.get()) lastUpdateId.set(uid);
                    Message m = u.message();
                    if (m == null) continue;
                    String text = m.text();
                    if (text == null) continue;
                    User from = m.from();
                    onMessage.accept(new RawMessage(
                            m.chat().id(),
                            from == null ? null : from.username(),
                            from == null ? null : from.firstName(),
                            from == null ? null : from.lastName(),
                            text));
                }
            } catch (Exception e) {
                if (running.get()) {
                    warnOnce("telegram poll loop error: " + e);
                    sleepBackoff(5000);
                }
            }
        }
    }

    private void warnOnce(String msg) {
        long now = System.currentTimeMillis();
        long last = lastWarnAtMs.get();
        if (now - last >= 60_000 && lastWarnAtMs.compareAndSet(last, now)) {
            log.warn(msg);
        }
    }

    private void sleepBackoff(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static final class Builder {
        private String botToken = "";
        private String apiUrl = "";
        private int pollTimeoutSeconds = 25;
        private Consumer<RawMessage> onMessage = m -> {};

        public Builder botToken(String v) { this.botToken = v; return this; }
        public Builder apiUrl(String v) { this.apiUrl = v; return this; }
        public Builder pollTimeoutSeconds(int v) { this.pollTimeoutSeconds = v; return this; }
        public Builder onMessage(Consumer<RawMessage> v) { this.onMessage = v; return this; }
        public TelegramClient build() { return new TelegramClient(this); }
    }
}
