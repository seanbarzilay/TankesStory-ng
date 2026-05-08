package server.bot;

import client.Character;
import config.BotConfig;

public class DefaultBotBrain implements BotBrain {

    private final BotConfig cfg;
    private final WorldView world;
    private final BotActuator actuator;

    public DefaultBotBrain(BotConfig cfg, WorldView world) {
        this(cfg, world, new LoggingBotActuator());
    }

    public DefaultBotBrain(BotConfig cfg, WorldView world, BotActuator actuator) {
        this.cfg = cfg;
        this.world = world;
        this.actuator = actuator;
    }

    @Override
    public void tick(Bot bot, long now) {
        actuator.tickPassive(bot, now);
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
        if (cfg.auto_accept_party && world.hasPendingPartyInvite(bot)) {
            return BotAction.ACCEPT_PARTY_INVITE;
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
        if (bot.mode() == Bot.Mode.GRIND) {
            java.util.List<Integer> mobs = world.nearbyMobIds(bot, cfg.grind_radius);
            if (mobs.isEmpty()) return BotAction.IDLE;
            int target = mobs.get(0);
            if (!world.mobInAttackRange(bot, target)) return BotAction.STEP_TOWARD_MOB;
            return world.isRangedWeapon(bot) ? BotAction.ATTACK_RANGED : BotAction.ATTACK_MELEE;
        }
        if (world.hasItemDropInPickupRadius(bot)
                && world.hasInventorySpaceForNearbyDrops(bot)) {
            return BotAction.PICKUP;
        }
        return BotAction.IDLE;
    }

    private static boolean hasItem(Character chr, int itemId) {
        var inv = chr.getInventory(client.inventory.InventoryType.USE);
        if (inv == null) return false;
        return inv.findById(itemId) != null;
    }

    void execute(Bot bot, BotAction action, long now) {
        switch (action) {
            case IDLE -> { /* nothing */ }
            case USE_HP_POT -> actuator.useHpPot(bot);
            case USE_MP_POT -> actuator.useMpPot(bot);
            case RETREAT -> actuator.retreatStep(bot);
            case WAIT_REVIVE -> actuator.scheduleRevive(bot, cfg.revive_delay_ms);
            case ACCEPT_PARTY_INVITE -> actuator.acceptPartyInvite(bot);
            case WALK_TO_PORTAL -> {
                Integer t = bot.targetCharId();
                if (t == null) return;
                Character target = world.findCharacterById(t);
                if (target != null) actuator.walkToPortal(bot, target.getMapId());
            }
            case STEP_TOWARD_TARGET -> {
                if (bot.targetCharId() != null) actuator.stepTowardTarget(bot, bot.targetCharId());
            }
            case STEP_TOWARD_MOB -> {
                java.util.List<Integer> mobs = world.nearbyMobIds(bot, cfg.grind_radius);
                if (!mobs.isEmpty()) actuator.stepTowardMob(bot, mobs.get(0));
            }
            case ATTACK_MELEE -> {
                java.util.List<Integer> mobs = world.nearbyMobIds(bot, cfg.grind_radius);
                if (!mobs.isEmpty()) actuator.attackMelee(bot, mobs.get(0));
            }
            case ATTACK_RANGED -> {
                java.util.List<Integer> mobs = world.nearbyMobIds(bot, cfg.grind_radius);
                if (!mobs.isEmpty()) actuator.attackRanged(bot, mobs.get(0));
            }
            case PICKUP -> actuator.pickup(bot);
        }
    }
}
