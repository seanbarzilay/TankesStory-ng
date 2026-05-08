package server.bot;

/**
 * Side-effecting half of the bot brain: each non-IDLE {@link BotAction}
 * routes to one of these methods. The v1 default is a no-op
 * {@link LoggingBotActuator}; v1.1 will replace it with a {@code MapActuator}
 * that emits real packets, applies damage, and accepts party invites.
 */
public interface BotActuator {
    void useHpPot(Bot bot);
    void useMpPot(Bot bot);
    void retreatStep(Bot bot);
    void scheduleRevive(Bot bot, int delayMs);
    void acceptPartyInvite(Bot bot);
    void walkToPortal(Bot bot, int targetMapId);
    void stepTowardTarget(Bot bot, int targetCharId);
    void stepTowardMob(Bot bot, int mobId);
    void attackMelee(Bot bot, int mobId);
    void attackRanged(Bot bot, int mobId);
    void pickup(Bot bot);

    /**
     * Per-tick passive simulation that runs before decide/execute. Default
     * is a no-op; production {@code MapActuator} uses this to apply mob
     * touch damage to the bot since the bot has no real client to send
     * take-damage packets.
     */
    default void tickPassive(Bot bot, long now) {}
}
