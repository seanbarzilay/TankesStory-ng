package server.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stub actuator that logs each action — production v1.1 will replace with MapActuator. */
public class LoggingBotActuator implements BotActuator {
    private static final Logger log = LoggerFactory.getLogger(LoggingBotActuator.class);

    @Override public void useHpPot(Bot bot) { log.debug("bot {} useHpPot", bot.id()); }
    @Override public void useMpPot(Bot bot) { log.debug("bot {} useMpPot", bot.id()); }
    @Override public void retreatStep(Bot bot) { log.debug("bot {} retreat", bot.id()); }
    @Override public void scheduleRevive(Bot bot, int delayMs) { log.debug("bot {} scheduleRevive {}ms", bot.id(), delayMs); }
    @Override public void acceptPartyInvite(Bot bot) { log.debug("bot {} acceptPartyInvite", bot.id()); }
    @Override public void walkToPortal(Bot bot, int targetMapId) { log.debug("bot {} walkToPortal -> {}", bot.id(), targetMapId); }
    @Override public void stepTowardTarget(Bot bot, int targetCharId) { log.debug("bot {} stepTowardTarget {}", bot.id(), targetCharId); }
    @Override public void stepTowardMob(Bot bot, int mobId) { log.debug("bot {} stepTowardMob {}", bot.id(), mobId); }
    @Override public void attackMelee(Bot bot, int mobId) { log.debug("bot {} attackMelee {}", bot.id(), mobId); }
    @Override public void attackRanged(Bot bot, int mobId) { log.debug("bot {} attackRanged {}", bot.id(), mobId); }
    @Override public void pickup(Bot bot) { log.debug("bot {} pickup", bot.id()); }
}
