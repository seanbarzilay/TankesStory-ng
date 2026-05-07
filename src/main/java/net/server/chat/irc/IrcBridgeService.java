package net.server.chat.irc;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class IrcBridgeService {

    private static final AtomicReference<IrcBridgeService> INSTANCE = new AtomicReference<>();

    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    public IrcBridgeService(WorldChatService worldChat, RateLimiter rateLimiter) {
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<IrcBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(IrcBridgeService svc) {
        INSTANCE.set(svc);
    }

    public static void clearInstance() {
        INSTANCE.set(null);
    }
}
