package net.server.chat.telegram;

import client.command.CommandsExecutor;
import client.command.commands.gm0.WorldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramBridgeService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBridgeService.class);
    private static final AtomicReference<TelegramBridgeService> INSTANCE = new AtomicReference<>();

    private final TelegramClient client;
    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    private TelegramBridgeService(TelegramClient client, WorldChatService worldChat, RateLimiter rateLimiter) {
        this.client = client;
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<TelegramBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(TelegramBridgeService svc) { INSTANCE.set(svc); }
    public static void clearInstance() { INSTANCE.set(null); }

    public static TelegramBridgeService start(TelegramConfig cfg, WorldBroadcaster broadcaster, Clock clock) {
        if (!cfg.enabled()) {
            log.info("Telegram bridge disabled");
            return null;
        }
        if (!cfg.isValid()) {
            log.warn("Telegram bridge enabled but config is invalid: {}", cfg.validationError());
            return null;
        }

        WorldChannelMap chats = WorldChannelMap.of(cfg.chats());
        AtomicReference<WorldChatService> serviceRef = new AtomicReference<>();

        TelegramClient client = new TelegramClient.Builder()
                .botToken(cfg.botToken())
                .apiUrl(cfg.apiUrl())
                .pollTimeoutSeconds(cfg.pollTimeoutSeconds())
                .onMessage(raw -> {
                    chats.worldFor(raw.chatId()).ifPresent(worldId -> {
                        String displayName = displayNameFor(raw);
                        serviceRef.get().deliverFromTelegram(worldId, displayName, raw.text());
                    });
                })
                .build();

        WorldChatService chat = new WorldChatService(chats, client, broadcaster, cfg.maxLength());
        serviceRef.set(chat);

        RateLimiter rl = new RateLimiter(cfg.rateLimitPerMinute(), clock);
        TelegramBridgeService svc = new TelegramBridgeService(client, chat, rl);
        setInstance(svc);

        CommandsExecutor.getInstance().registerLv0Command("world", WorldCommand.class);
        client.start();
        log.info("Telegram bridge started: chats={}", cfg.chats());
        return svc;
    }

    private static String displayNameFor(TelegramClient.RawMessage raw) {
        if (raw.fromUsername() != null && !raw.fromUsername().isBlank()) {
            return "@" + raw.fromUsername();
        }
        StringBuilder sb = new StringBuilder();
        if (raw.fromFirstName() != null) sb.append(raw.fromFirstName());
        if (raw.fromLastName() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(raw.fromLastName());
        }
        String name = sb.toString().strip();
        return name.isEmpty() ? "anon" : name;
    }

    public void stop(long timeoutMs) {
        try {
            CommandsExecutor.getInstance().unregisterCommand("world");
        } catch (Exception e) {
            log.warn("failed to unregister @world: {}", e.toString());
        }
        client.stop(timeoutMs);
        clearInstance();
        log.info("Telegram bridge stopped");
    }
}
