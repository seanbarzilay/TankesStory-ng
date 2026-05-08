package server.bot;

import client.Character;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FakeWorldView implements WorldView {
    final Map<Integer, Character> chars = new HashMap<>();
    List<Integer> nearbyMobs = List.of();
    boolean hasItem = false;
    boolean hasInvSpace = true;
    boolean hasInvite = false;
    int nearestPortalToTarget = -1;
    boolean ranged = false;
    boolean inRange = false;

    @Override public Character findCharacterById(int id) { return chars.get(id); }
    @Override public List<Integer> nearbyMobIds(Bot bot, int radius) { return nearbyMobs; }
    @Override public boolean hasItemDropInPickupRadius(Bot bot) { return hasItem; }
    @Override public boolean hasInventorySpaceForNearbyDrops(Bot bot) { return hasInvSpace; }
    @Override public boolean hasPendingPartyInvite(Bot bot) { return hasInvite; }
    @Override public int findNearestPortalToMap(Bot bot, int t) { return nearestPortalToTarget; }
    @Override public boolean isRangedWeapon(Bot bot) { return ranged; }
    @Override public boolean mobInAttackRange(Bot bot, int mobId) { return inRange; }
}
