package server.bot;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import config.BotConfig;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import server.ItemInformationProvider;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link WorldView} that wraps {@link Server#getInstance()} and
 * {@link MapleMap}. Loot- and weapon-type related lookups return safe
 * defaults for v1 — those are TODO follow-ups for v1.1, gated on the
 * matching actuator implementations.
 */
public class ServerWorldView implements WorldView {

    private final BotConfig cfg;

    public ServerWorldView(BotConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public Character findCharacterById(int id) {
        try {
            for (net.server.world.World w : Server.getInstance().getWorlds()) {
                Character chr = w.getPlayerStorage().getCharacterById(id);
                if (chr != null) return chr;
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    @Override
    public List<Integer> nearbyMobIds(Bot bot, int radius) {
        Character chr = bot.character();
        MapleMap map = chr.getMap();
        if (map == null) return List.of();
        List<Integer> ids = new ArrayList<>();
        Point pos = chr.getPosition();
        long r2 = (long) radius * (long) radius;
        for (MapObject obj : map.getMapObjects()) {
            if (obj instanceof Monster m) {
                long dx = m.getPosition().x - pos.x;
                long dy = m.getPosition().y - pos.y;
                if (dx * dx + dy * dy <= r2) {
                    ids.add(m.getObjectId());
                }
            }
        }
        return ids;
    }

    @Override
    public boolean hasItemDropInPickupRadius(Bot bot) {
        // TODO follow-up: scan map for MapItem objects within pickup radius.
        return false;
    }

    @Override
    public boolean hasInventorySpaceForNearbyDrops(Bot bot) {
        return true;
    }

    @Override
    public boolean hasPendingPartyInvite(Bot bot) {
        try {
            return InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, bot.id());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int findNearestPortalToMap(Bot bot, int targetMapId) {
        Character chr = bot.character();
        MapleMap map = chr.getMap();
        if (map == null) return -1;
        Point pos = chr.getPosition();
        int bestId = -1;
        long bestD2 = Long.MAX_VALUE;
        for (Portal p : map.getPortals()) {
            if (p.getTargetMapId() == targetMapId) {
                long dx = p.getPosition().x - pos.x;
                long dy = p.getPosition().y - pos.y;
                long d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestId = p.getId();
                }
            }
        }
        return bestId;
    }

    @Override
    public boolean isRangedWeapon(Bot bot) {
        try {
            Item weapon = bot.character()
                    .getInventory(InventoryType.EQUIPPED)
                    .getItem((short) -11);
            if (weapon == null) return false;
            WeaponType type = ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
            return type == WeaponType.BOW
                    || type == WeaponType.CROSSBOW
                    || type == WeaponType.CLAW
                    || type == WeaponType.GUN;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean mobInAttackRange(Bot bot, int mobId) {
        try {
            Character chr = bot.character();
            MapleMap map = chr.getMap();
            if (map == null) return false;
            Point pos = chr.getPosition();
            for (MapObject obj : map.getMapObjects()) {
                if (obj instanceof Monster m && m.getObjectId() == mobId) {
                    long dx = m.getPosition().x - pos.x;
                    long dy = m.getPosition().y - pos.y;
                    return dx * dx + dy * dy <= 200L * 200L;
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return false;
    }
}
