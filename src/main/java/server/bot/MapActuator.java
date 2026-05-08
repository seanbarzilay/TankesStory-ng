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

    @FunctionalInterface
    public interface DelayedScheduler {
        void schedule(Runnable r, long delayMs);
    }

    @FunctionalInterface
    public interface PartyJoiner {
        boolean join(Character player, int partyId);
    }

    private static final Logger log = LoggerFactory.getLogger(MapActuator.class);
    private static final int STEP_DURATION_MS = 200;
    private static final int STEP_PX = 60;

    private final BotConfig cfg;
    private final CharacterLookup characterLookup;
    private final EffectLookup effectLookup;
    private final DelayedScheduler scheduler;
    private final PartyJoiner partyJoiner;

    public MapActuator(BotConfig cfg) {
        this(cfg, MapActuator::serverCharacterLookup,
                id -> server.ItemInformationProvider.getInstance().getItemEffect(id),
                (r, ms) -> server.TimerManager.getInstance().schedule(r, ms),
                (chr, partyId) -> net.server.world.Party.joinParty(chr, partyId, /*silentCheck=*/true));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup) {
        this(cfg, lookup,
                id -> server.ItemInformationProvider.getInstance().getItemEffect(id),
                (r, ms) -> server.TimerManager.getInstance().schedule(r, ms),
                (chr, partyId) -> net.server.world.Party.joinParty(chr, partyId, /*silentCheck=*/true));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup, EffectLookup effectLookup) {
        this(cfg, lookup, effectLookup,
                (r, ms) -> server.TimerManager.getInstance().schedule(r, ms),
                (chr, partyId) -> net.server.world.Party.joinParty(chr, partyId, /*silentCheck=*/true));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup, EffectLookup effectLookup,
                       DelayedScheduler scheduler) {
        this(cfg, lookup, effectLookup, scheduler,
                (chr, partyId) -> net.server.world.Party.joinParty(chr, partyId, /*silentCheck=*/true));
    }

    public MapActuator(BotConfig cfg, CharacterLookup lookup, EffectLookup effectLookup,
                       DelayedScheduler scheduler, PartyJoiner partyJoiner) {
        this.cfg = cfg;
        this.characterLookup = lookup;
        this.effectLookup = effectLookup;
        this.scheduler = scheduler;
        this.partyJoiner = partyJoiner;
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
     *
     * <p>Computes a directional walk stance (2=walk-right, 3=walk-left) and a
     * wobble that matches the actual step velocity so the v83 client interpolates
     * smoothly across the step duration without snapping. Looks up the foothold
     * under the destination so the bot appears to walk on the ground rather than
     * mid-air.
     */
    Packet broadcastStep(Bot bot, Point dst) {
        Character chr = bot.character();
        Point cur = chr.getPosition();
        int dx = dst.x - cur.x;
        // v83 stance convention: even = facing right, odd = facing left.
        // 2 = walk right, 3 = walk left.
        int stance = (dx > 0) ? 2 : (dx < 0 ? 3 : MoveBuilder.STANCE_STAND_RIGHT);
        // Wobble must equal actual velocity (px/s) or the client jitters between
        // its interpolation and the next absolute position. STEP_PX over
        // STEP_DURATION_MS in milliseconds → px/s.
        int vx = (int) Math.round(dx * 1000.0 / STEP_DURATION_MS);
        Point wobble = new Point(vx, 0);
        int fh = 0;
        MapleMap map = chr.getMap();
        if (map != null && map.getFootholds() != null) {
            try {
                server.maps.Foothold f = map.getFootholds().findBelow(dst);
                if (f != null) fh = f.getId();
            } catch (Throwable t) { /* fall back to 0 */ }
        }
        OutPacket op = OutPacket.create(SendOpcode.MOVE_PLAYER);
        op.writeInt(chr.getId());
        op.writeInt(0);
        MoveBuilder.serializeAbsoluteStep(op, dst, wobble, stance, STEP_DURATION_MS, fh);
        chr.setPosition(new Point(dst));
        chr.setStance(stance);
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
    @Override
    public void scheduleRevive(Bot bot, int delayMs) {
        scheduler.schedule(() -> {
            try {
                Character chr = bot.character();
                // setHp/setMp are protected on AbstractCharacterObject; use addHP/addMP.
                int needHp = chr.getMaxHp() - chr.getHp();
                int needMp = chr.getMaxMp() - chr.getMp();
                if (needHp > 0) chr.addHP(needHp);
                if (needMp > 0) chr.addMP(needMp);
                server.maps.MapleMap map = chr.getMap();
                if (map != null && chr.getClient() != null) {
                    Packet spawn = tools.PacketCreator.spawnPlayerMapObject(
                            chr.getClient(), chr, /*enteringField=*/false);
                    map.broadcastMessage(chr, spawn, /*repeatToSource=*/false);
                }
            } catch (Throwable t) {
                log.warn("scheduleRevive runnable failed for bot {}", bot.id(), t);
            }
        }, delayMs);
    }
    @Override
    public void acceptPartyInvite(Bot bot) {
        Character chr = bot.character();
        Integer inviterId;
        try {
            inviterId = net.server.coordinator.world.InviteCoordinator.peekInviterId(
                    net.server.coordinator.world.InviteCoordinator.InviteType.PARTY, chr.getId());
        } catch (Throwable t) {
            return;
        }
        if (inviterId == null || inviterId <= 0) return;
        Character inviter = characterLookup.byId(inviterId);
        if (inviter == null) return;
        net.server.world.Party party = inviter.getParty();
        if (party == null) return;
        partyJoiner.join(chr, party.getId());
    }
    @Override
    public void walkToPortal(Bot bot, int targetMapId) {
        Character chr = bot.character();
        server.maps.MapleMap map = chr.getMap();
        if (map == null) return;
        server.maps.Portal best = null;
        long bestD2 = Long.MAX_VALUE;
        for (server.maps.Portal p : map.getPortals()) {
            if (p.getTargetMapId() != targetMapId) continue;
            int dx = p.getPosition().x - chr.getPosition().x;
            int dy = p.getPosition().y - chr.getPosition().y;
            long d2 = (long) dx * dx + (long) dy * dy;
            if (d2 < bestD2) {
                best = p;
                bestD2 = d2;
            }
        }
        if (best == null) return;
        if (bestD2 <= (long) PORTAL_ADJACENT_PX * PORTAL_ADJACENT_PX) {
            chr.changeMap(targetMapId, best);
        } else {
            broadcastStep(bot, stepToward(chr.getPosition(), best.getPosition()));
        }
    }

    private static final int PORTAL_ADJACENT_PX = 50;
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
