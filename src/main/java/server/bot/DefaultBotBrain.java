package server.bot;

import config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultBotBrain implements BotBrain {

    private static final Logger log = LoggerFactory.getLogger(DefaultBotBrain.class);

    private final BotConfig cfg;
    private final WorldView world;

    public DefaultBotBrain(BotConfig cfg, WorldView world) {
        this.cfg = cfg;
        this.world = world;
    }

    @Override
    public void tick(Bot bot, long now) {
        BotAction action = decide(bot, now);
        execute(bot, action, now);
    }

    /** Visible for tests. */
    public BotAction decide(Bot bot, long now) {
        // Tasks 9-14 will fill in the priority-ordered branches.
        return BotAction.IDLE;
    }

    void execute(Bot bot, BotAction action, long now) {
        if (action == BotAction.IDLE) return;
        // Tasks 9-14 add execution branches; Task 19 routes to BotActuator.
    }
}
