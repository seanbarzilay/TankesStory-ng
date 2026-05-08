package server.bot;

import client.Character;

import java.util.List;

/**
 * Read-only view the brain uses to look at server state.
 * Production impl wraps Server.getInstance() and MapleMap (Task 19).
 * Tests provide a fake.
 */
public interface WorldView {
    Character findCharacterById(int id);
    List<Integer> nearbyMobIds(Bot bot, int radius);
    boolean hasItemDropInPickupRadius(Bot bot);
    boolean hasInventorySpaceForNearbyDrops(Bot bot);
    boolean hasPendingPartyInvite(Bot bot);
    int findNearestPortalToMap(Bot bot, int targetMapId); // -1 if none
    boolean isRangedWeapon(Bot bot);
    boolean mobInAttackRange(Bot bot, int mobId);
}
