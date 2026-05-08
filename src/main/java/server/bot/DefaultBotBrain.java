package server.bot;

import client.Character;
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
        Character chr = bot.character();
        if (!chr.isAlive()) return BotAction.WAIT_REVIVE;
        int hpPct = chr.getMaxHp() == 0 ? 100 : (chr.getHp() * 100 / chr.getMaxHp());
        if (hpPct < cfg.hp_pct_threshold) {
            if (hasItem(chr, cfg.hp_pot_item_id)) return BotAction.USE_HP_POT;
            return BotAction.RETREAT;
        }
        int mpPct = chr.getMaxMp() == 0 ? 100 : (chr.getMp() * 100 / chr.getMaxMp());
        if (mpPct < cfg.mp_pct_threshold) {
            if (hasItem(chr, cfg.mp_pot_item_id)) return BotAction.USE_MP_POT;
        }
        if (bot.mode() == Bot.Mode.FOLLOW && bot.targetCharId() != null) {
            Character target = world.findCharacterById(bot.targetCharId());
            if (target == null) {
                bot.setTargetCharId(null);
                return BotAction.IDLE;
            }
            if (target.getMapId() != chr.getMapId()) {
                int portalId = world.findNearestPortalToMap(bot, target.getMapId());
                return portalId >= 0 ? BotAction.WALK_TO_PORTAL : BotAction.IDLE;
            }
            int dx = target.getPosition().x - chr.getPosition().x;
            int dy = target.getPosition().y - chr.getPosition().y;
            int distSq = dx*dx + dy*dy;
            if (distSq <= cfg.follow_radius * cfg.follow_radius) return BotAction.IDLE;
            return BotAction.STEP_TOWARD_TARGET;
        }
        return BotAction.IDLE;
    }

    private static boolean hasItem(Character chr, int itemId) {
        var inv = chr.getInventory(client.inventory.InventoryType.USE);
        if (inv == null) return false;
        return inv.findById(itemId) != null;
    }

    void execute(Bot bot, BotAction action, long now) {
        if (action == BotAction.IDLE) return;
        // Tasks 9-14 add execution branches; Task 19 routes to BotActuator.
    }
}
