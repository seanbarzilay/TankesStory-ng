package net.server.chat.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.ResponseParameters;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** Per-chat consecutive send-failure counter; reaching 5 mutes that chat until restart. */
    private final ConcurrentHashMap<Long, AtomicInteger> chatFailureCount = new ConcurrentHashMap<>();
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
        // Shut down OkHttp dispatcher first so in-flight long-poll requests
        // fail immediately with IOException, unblocking the polling thread.
        try { bot.shutdown(); } catch (Exception ignored) {}
        if (pollThread != null) pollThread.interrupt();
        try {
            if (pollThread != null) pollThread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void sendToChat(long chatId, String text) {
        if (!running.get()) return;
        // If this chat has hit 5 consecutive failures, skip silently until restart.
        AtomicInteger counter = chatFailureCount.computeIfAbsent(chatId, id -> new AtomicInteger(0));
        if (counter.get() >= 5) return;
        try {
            bot.execute(new SendMessage(chatId, text), new com.pengrad.telegrambot.Callback<>() {
                @Override public void onResponse(SendMessage req, SendResponse resp) {
                    if (resp.isOk()) {
                        counter.set(0);
                    } else {
                        int failures = counter.incrementAndGet();
                        warnOnce("telegram sendMessage rejected: chat=" + chatId
                                + " code=" + resp.errorCode() + " desc=" + resp.description());
                        if (failures >= 5) {
                            log.warn("telegram sendMessage: chat={} muted after 5 consecutive failures", chatId);
                        }
                    }
                }
                @Override public void onFailure(SendMessage req, IOException e) {
                    int failures = counter.incrementAndGet();
                    warnOnce("telegram sendMessage failed: chat=" + chatId + " err=" + e);
                    if (failures >= 5) {
                        log.warn("telegram sendMessage: chat={} muted after 5 consecutive failures", chatId);
                    }
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
                    long backoffMs = 5000;
                    if (resp != null) {
                        ResponseParameters params = resp.parameters();
                        if (params != null) {
                            Integer retryAfter = params.retryAfter();
                            if (retryAfter != null && retryAfter > 0) {
                                // Clamp to [1, 60] seconds as per spec
                                long clamped = Math.min(60, Math.max(1, retryAfter));
                                backoffMs = clamped * 1000L;
                                log.warn("telegram getUpdates: rate-limited, sleeping {}s (retry_after={})", clamped, retryAfter);
                            }
                        }
                    }
                    sleepBackoff(backoffMs);
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
