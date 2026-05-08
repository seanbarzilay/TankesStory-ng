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

    @FunctionalInterface
    public interface CharacterLookup {
        Character byId(int id); // returns null if not found
    }

    @FunctionalInterface
    public interface EffectLookup {
        server.StatEffect byId(int itemId); // returns null when WZ data is unavailable
    }

    private static final Logger log = LoggerFactory.getLogger(MapActuator.class);
    private static final int STEP_DURATION_MS = 200;
    private static final int STEP_PX = 60;

    private final BotConfig cfg;
    private final CharacterLookup characterLookup;
    private final EffectLookup effectLookup;

    public MapActuator(BotConfig cfg) {
        this(cfg, MapActuator::serverCharacterLookup,
                id -> server.ItemInformationProvider.getInstance().getItemEffect(id));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup) {
        this(cfg, lookup,
                id -> server.ItemInformationProvider.getInstance().getItemEffect(id));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup, EffectLookup effectLookup) {
        this.cfg = cfg;
        this.characterLookup = lookup;
        this.effectLookup = effectLookup;
    }

    private static Character serverCharacterLookup(int id) {
        try {
            for (net.server.world.World w : net.server.Server.getInstance().getWorlds()) {
                Character chr = w.getPlayerStorage().getCharacterById(id);
                if (chr != null) return chr;
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
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

    @Override
    public void stepTowardTarget(Bot bot, int targetCharId) {
        Character target = characterLookup.byId(targetCharId);
        if (target == null) return;
        broadcastStep(bot, stepToward(bot.character().getPosition(), target.getPosition()));
    }

    @Override
    public void stepTowardMob(Bot bot, int mobId) {
        server.maps.MapleMap map = bot.character().getMap();
        if (map == null) return;
        server.life.Monster mob = map.getMonsterByOid(mobId);
        if (mob == null) return;
        broadcastStep(bot, stepToward(bot.character().getPosition(), mob.getPosition()));
    }

    @Override
    public void retreatStep(Bot bot) {
        Point cur = bot.character().getPosition();
        broadcastStep(bot, new Point(cur.x - STEP_PX, cur.y));
    }

    private static Point stepToward(Point from, Point to) {
        int dx = to.x - from.x;
        int dy = to.y - from.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= STEP_PX || dist == 0) return new Point(to);
        int sx = (int) Math.round(from.x + dx * STEP_PX / dist);
        int sy = (int) Math.round(from.y + dy * STEP_PX / dist);
        return new Point(sx, sy);
    }

    @Override
    public void useHpPot(Bot bot) {
        drinkPot(bot, cfg.hp_pot_item_id, /*hp=*/true);
    }

    @Override
    public void useMpPot(Bot bot) {
        drinkPot(bot, cfg.mp_pot_item_id, /*hp=*/false);
    }

    private void drinkPot(Bot bot, int potItemId, boolean hp) {
        Character chr = bot.character();
        client.inventory.Inventory inv = chr.getInventory(client.inventory.InventoryType.USE);
        if (inv == null) return;
        client.inventory.Item pot = inv.findById(potItemId);
        if (pot == null) return;

        server.StatEffect effect = effectLookup.byId(potItemId);
        if (effect == null) return;
        int heal = hp ? effect.getHp() : effect.getMp();
        if (heal <= 0) return;

        if (hp) {
            chr.addHP(heal);
        } else {
            chr.addMP(heal);
        }

        short newQty = (short) (pot.getQuantity() - 1);
        if (newQty <= 0) {
            inv.removeItem(pot.getPosition());
        } else {
            pot.setQuantity(newQty);
        }
    }
    @Override public void scheduleRevive(Bot bot, int delayMs) { log.debug("MapActuator scheduleRevive {} (TODO)", bot.id()); }
    @Override public void acceptPartyInvite(Bot bot) { log.debug("MapActuator acceptPartyInvite {} (TODO)", bot.id()); }
    @Override public void walkToPortal(Bot bot, int targetMapId) { log.debug("MapActuator walkToPortal {} (TODO)", bot.id()); }
    @Override
    public void attackMelee(Bot bot, int mobId) {
        Character chr = bot.character();
        server.maps.MapleMap map = chr.getMap();
        if (map == null) return;
        server.life.Monster mob = map.getMonsterByOid(mobId);
        if (mob == null) return;

        int damage = Math.max(1, chr.getLevel() * 10);
        java.util.Map<Integer, net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget> targets =
                new java.util.HashMap<>();
        targets.put(mob.getObjectId(),
                new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                        /*delay=*/(short) 0, java.util.List.of(damage)));

        // numAttackedAndDamage encodes (numAttacked << 4) | numDamage; for 1 mob, 1 damage line: (1 << 4) | 1 = 0x11
        net.packet.Packet packet = tools.PacketCreator.closeRangeAttack(
                chr, /*skill=*/0, /*skilllevel=*/0, /*stance=*/MoveBuilder.STANCE_STAND_RIGHT,
                /*numAttackedAndDamage=*/(1 << 4) | 1, targets,
                /*speed=*/4, /*direction=*/0, /*display=*/0);
        map.broadcastMessage(chr, packet, /*repeatToSource=*/false);
        map.damageMonster(chr, mob, damage);
    }
    @Override
    public void attackRanged(Bot bot, int mobId) {
        Character chr = bot.character();
        server.maps.MapleMap map = chr.getMap();
        if (map == null) return;
        server.life.Monster mob = map.getMonsterByOid(mobId);
        if (mob == null) return;

        int damage = Math.max(1, chr.getLevel() * 10);
        java.util.Map<Integer, net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget> targets =
                new java.util.HashMap<>();
        targets.put(mob.getObjectId(),
                new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                        (short) 0, java.util.List.of(damage)));

        // numAttackedAndDamage encodes (numAttacked << 4) | numDamage; for 1 mob, 1 damage line: (1 << 4) | 1 = 0x11
        net.packet.Packet packet = tools.PacketCreator.rangedAttack(
                chr, /*skill=*/0, /*skilllevel=*/0, /*stance=*/MoveBuilder.STANCE_STAND_RIGHT,
                /*numAttackedAndDamage=*/(1 << 4) | 1, /*projectile=*/0, targets,
                /*speed=*/4, /*direction=*/0, /*display=*/0);
        map.broadcastMessage(chr, packet, false);
        map.damageMonster(chr, mob, damage);
    }
    @Override
    public void pickup(Bot bot) {
        Character chr = bot.character();
        server.maps.MapleMap map = chr.getMap();
        if (map == null) return;
        Point pos = chr.getPosition();
        int r2 = PICKUP_RADIUS_PX * PICKUP_RADIUS_PX;
        server.maps.MapItem nearest = null;
        long nearestD2 = Long.MAX_VALUE;
        for (server.maps.MapObject obj : map.getMapObjects()) {
            if (obj instanceof server.maps.MapItem mi) {
                long dx = mi.getPosition().x - pos.x;
                long dy = mi.getPosition().y - pos.y;
                long d2 = dx * dx + dy * dy;
                if (d2 <= r2 && d2 < nearestD2) {
                    nearest = mi;
                    nearestD2 = d2;
                }
            }
        }
        if (nearest == null) return;
        Packet pickupPkt = tools.PacketCreator.removeItemFromMap(
                nearest.getObjectId(), /*animation=*/2, chr.getId());
        map.pickItemDrop(pickupPkt, nearest);
    }

    static final int PICKUP_RADIUS_PX = 100;
}
