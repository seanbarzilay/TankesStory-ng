package net.server.chat.telegram;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramBridgeService {

    private static final AtomicReference<TelegramBridgeService> INSTANCE = new AtomicReference<>();

    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    public TelegramBridgeService(WorldChatService worldChat, RateLimiter rateLimiter) {
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
}
