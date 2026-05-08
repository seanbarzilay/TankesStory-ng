package server.bot;

import client.Character;
import config.BotConfig;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.awt.Point;

public class MapActuator implements BotActuator {

    private static final Logger log = LoggerFactory.getLogger(MapActuator.class);
    private static final int STEP_DURATION_MS = 200;

    private final BotConfig cfg;

    public MapActuator(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Builds and broadcasts a MOVE_PLAYER packet that walks the bot to {@code dst}.
     * Returns the packet for assertion in tests.
     */
    Packet broadcastStep(Bot bot, Point dst) {
        Character chr = bot.character();
        OutPacket op = OutPacket.create(SendOpcode.MOVE_PLAYER);
        op.writeInt(chr.getId());
        op.writeInt(0);
        MoveBuilder.serializeAbsoluteStep(op, dst, MoveBuilder.STANCE_STAND_RIGHT,
                STEP_DURATION_MS, /*fh=*/0);
        chr.setPosition(new Point(dst));
        MapleMap map = chr.getMap();
        if (map != null) {
            map.broadcastMessage(chr, op, /*repeatToSource=*/false);
        }
        return op;
    }

    @Override public void useHpPot(Bot bot) { log.debug("MapActuator useHpPot {} (TODO)", bot.id()); }
    @Override public void useMpPot(Bot bot) { log.debug("MapActuator useMpPot {} (TODO)", bot.id()); }
    @Override public void retreatStep(Bot bot) { log.debug("MapActuator retreat {} (TODO)", bot.id()); }
    @Override public void scheduleRevive(Bot bot, int delayMs) { log.debug("MapActuator scheduleRevive {} (TODO)", bot.id()); }
    @Override public void acceptPartyInvite(Bot bot) { log.debug("MapActuator acceptPartyInvite {} (TODO)", bot.id()); }
    @Override public void walkToPortal(Bot bot, int targetMapId) { log.debug("MapActuator walkToPortal {} (TODO)", bot.id()); }
    @Override public void stepTowardTarget(Bot bot, int targetCharId) { log.debug("MapActuator stepTowardTarget {} (TODO)", bot.id()); }
    @Override public void stepTowardMob(Bot bot, int mobId) { log.debug("MapActuator stepTowardMob {} (TODO)", bot.id()); }
    @Override public void attackMelee(Bot bot, int mobId) { log.debug("MapActuator attackMelee {} (TODO)", bot.id()); }
    @Override public void attackRanged(Bot bot, int mobId) { log.debug("MapActuator attackRanged {} (TODO)", bot.id()); }
    @Override public void pickup(Bot bot) { log.debug("MapActuator pickup {} (TODO)", bot.id()); }
}
