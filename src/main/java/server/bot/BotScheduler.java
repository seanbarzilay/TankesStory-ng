package server.bot;

import config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final int CONSECUTIVE_FAILURE_LIMIT = 3;

    public interface Despawner { void despawn(Bot bot); }

    private final BotManager manager;
    private final BotBrain brain;
    private final BotConfig cfg;
    private final Despawner despawner;
    private final Map<Integer, Integer> consecutiveFailures = new HashMap<>();
    private ScheduledFuture<?> handle;

    public BotScheduler(BotManager manager, BotBrain brain, BotConfig cfg, Despawner despawner) {
        this.manager = manager;
        this.brain = brain;
        this.cfg = cfg;
        this.despawner = despawner;
    }

    public void start() {
        handle = TimerManager.getInstance().register(
                () -> runOnce(System.currentTimeMillis()),
                cfg.tick_ms);
    }

    public void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }

    /** Visible for tests. */
    public void runOnce(long now) {
        for (Bot bot : manager.activeBots()) {
            try {
                brain.tick(bot, now);
                consecutiveFailures.remove(bot.id());
            } catch (Throwable t) {
                int n = consecutiveFailures.getOrDefault(bot.id(), 0) + 1;
                consecutiveFailures.put(bot.id(), n);
                log.warn("bot tick failed (id={}, count={})", bot.id(), n, t);
                if (n >= CONSECUTIVE_FAILURE_LIMIT) {
                    log.warn("auto-despawning bot {} after {} consecutive failures",
                            bot.id(), n);
                    consecutiveFailures.remove(bot.id());
                    try { despawner.despawn(bot); } catch (Throwable t2) {
                        log.warn("despawn failed", t2);
                    }
                }
            }
        }
    }
}
