package net.server.chat.irc;

import client.command.CommandsExecutor;
import client.command.commands.gm0.WorldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class IrcBridgeService {

    private static final Logger log = LoggerFactory.getLogger(IrcBridgeService.class);
    private static final AtomicReference<IrcBridgeService> INSTANCE = new AtomicReference<>();

    private final IrcConnection connection;
    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    private IrcBridgeService(IrcConnection connection, WorldChatService worldChat, RateLimiter rateLimiter) {
        this.connection = connection;
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<IrcBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(IrcBridgeService svc) { INSTANCE.set(svc); }
    public static void clearInstance() { INSTANCE.set(null); }

    public static IrcBridgeService start(IrcConfig cfg, WorldBroadcaster broadcaster, Clock clock) {
        if (!cfg.enabled()) {
            log.info("IRC bridge disabled");
            return null;
        }
        if (!cfg.isValid()) {
            log.warn("IRC bridge enabled but config is invalid: {}", cfg.validationError());
            return null;
        }

        WorldChannelMap map = WorldChannelMap.of(cfg.channels());
        AtomicReference<WorldChatService> serviceRef = new AtomicReference<>();

        List<String> channelList = new ArrayList<>(map.allChannels());
        IrcConnection conn = new IrcConnection.Builder()
                .host(cfg.server()).port(cfg.port()).tls(cfg.tls())
                .nick(cfg.nick()).user(cfg.user()).realname(cfg.realname())
                .password(cfg.password())
                .channels(channelList).queueMax(cfg.outboundQueueMax())
                .backoffSeconds(cfg.reconnectBackoffSeconds())
                .onMessage(m -> {
                    if (!"PRIVMSG".equals(m.command()) || m.params().isEmpty()) return;
                    map.world(m.params().get(0)).ifPresent(worldId ->
                            serviceRef.get().deliverFromIrc(worldId, m.nick(), m.trailing()));
                })
                .build();

        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(java.time.Duration.ofMinutes(5), clock);
        PlayerSender playerSender = (worldId, charId, packet) -> {
            net.server.world.World w;
            try { w = net.server.Server.getInstance().getWorld(worldId); }
            catch (Exception e) { return false; }
            if (w == null) return false;
            client.Character ch = w.getPlayerStorage().getCharacterById(charId);
            if (ch == null) return false;
            ch.sendPacket(packet);
            return true;
        };
        WorldChatService chat = new WorldChatService(map, conn, broadcaster, tracker, playerSender, cfg.maxLength());
        serviceRef.set(chat);

        RateLimiter rl = new RateLimiter(cfg.rateLimitPerMinute(), clock);
        IrcBridgeService svc = new IrcBridgeService(conn, chat, rl);
        setInstance(svc);

        CommandsExecutor.getInstance().registerLv0Command("world", WorldCommand.class);
        conn.start();
        log.info("IRC bridge started: {}:{} channels={}", cfg.server(), cfg.port(), channelList);
        return svc;
    }

    public void stop(long timeoutMs) {
        try {
            CommandsExecutor.getInstance().unregisterCommand("world");
        } catch (Exception e) {
            log.warn("failed to unregister @world: {}", e.toString());
        }
        connection.stop(timeoutMs);
        clearInstance();
        log.info("IRC bridge stopped");
    }
}
