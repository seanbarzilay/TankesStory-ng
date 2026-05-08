package server.bot;

import client.Character;
import config.BotConfig;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import testutil.Mocks;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class MapActuatorTest {

    static Bot bot(int id) {
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(id);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        return new Bot(chr);
    }

    @Test
    void broadcastStepUpdatesPositionAndCallsBroadcast() {
        BotConfig cfg = new BotConfig();
        MapActuator a = new MapActuator(cfg);
        Bot b = bot(-1_000_000);
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        Point dst = new Point(50, 100);

        Packet pkt = a.broadcastStep(b, dst);
        assertNotNull(pkt);
        verify(b.character()).setPosition(eq(new Point(50, 100)));
        verify(map).broadcastMessage(same(b.character()), same(pkt), eq(false));
    }

    @Test
    void broadcastStepDoesNothingWhenMapIsNull() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getMap()).thenReturn(null);
        Packet pkt = a.broadcastStep(b, new Point(1, 1));
        assertNotNull(pkt, "still produces a packet, just doesn't broadcast");
    }

    @Test
    void stepTowardTargetMovesOneStepCloser() {
        Character target = Mocks.chr("Target");
        when(target.getPosition()).thenReturn(new Point(500, 0));
        MapActuator a = new MapActuator(new BotConfig(), id -> id == 999 ? target : null);
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);

        a.stepTowardTarget(b, 999);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }

    @Test
    void stepTowardTargetUnknownIdIsNoOp() {
        MapActuator a = new MapActuator(new BotConfig(), id -> null);
        Bot b = bot(-1_000_000);
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        a.stepTowardTarget(b, 999);
        verify(map, never()).broadcastMessage(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void stepTowardMobMovesOneStepCloser() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        server.life.Monster mob = mock(server.life.Monster.class);
        when(mob.getPosition()).thenReturn(new Point(300, 0));
        when(map.getMonsterByOid(42)).thenReturn(mob);
        a.stepTowardMob(b, 42);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }

    @Test
    void retreatBroadcastsAStep() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        a.retreatStep(b);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    }

    @Test
    void attackMeleeBroadcastsAttackAndAppliesDamage() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getLevel()).thenReturn(30);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        server.life.Monster mob = mock(server.life.Monster.class);
        when(mob.getObjectId()).thenReturn(42);
        when(mob.getPosition()).thenReturn(new Point(20, 0));
        when(map.getMonsterByOid(42)).thenReturn(mob);

        a.attackMelee(b, 42);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
        verify(map).damageMonster(same(b.character()), same(mob), org.mockito.ArgumentMatchers.intThat(d -> d > 0));
    }

    @Test
    void attackRangedBroadcastsAndAppliesDamage() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getLevel()).thenReturn(30);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        server.life.Monster mob = mock(server.life.Monster.class);
        when(mob.getObjectId()).thenReturn(42);
        when(map.getMonsterByOid(42)).thenReturn(mob);

        a.attackRanged(b, 42);
        verify(map).broadcastMessage(same(b.character()), any(), eq(false));
        verify(map).damageMonster(same(b.character()), same(mob), org.mockito.ArgumentMatchers.intThat(d -> d > 0));
    }

    @Test
    void useHpPotHealsAndDecrementsInventory() {
        BotConfig cfg = new BotConfig();
        Bot b = bot(-1_000_000);
        Character chr = b.character();
        client.inventory.Inventory use = mock(client.inventory.Inventory.class);
        when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
        client.inventory.Item pot = mock(client.inventory.Item.class);
        when(pot.getItemId()).thenReturn(cfg.hp_pot_item_id);
        when(pot.getQuantity()).thenReturn((short) 3);
        when(pot.getPosition()).thenReturn((short) 0);
        when(use.findById(cfg.hp_pot_item_id)).thenReturn(pot);

        server.StatEffect effect = mock(server.StatEffect.class);
        when(effect.getHp()).thenReturn((short) 500);

        MapActuator a = new MapActuator(cfg, id -> null, id -> effect);
        a.useHpPot(b);

        verify(chr).addHP(500);
        verify(pot).setQuantity((short) 2);
        verify(use, never()).removeItem(org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void useMpPotHealsAndRemovesEmptyStack() {
        BotConfig cfg = new BotConfig();
        Bot b = bot(-1_000_000);
        Character chr = b.character();
        client.inventory.Inventory use = mock(client.inventory.Inventory.class);
        when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
        client.inventory.Item pot = mock(client.inventory.Item.class);
        when(pot.getItemId()).thenReturn(cfg.mp_pot_item_id);
        when(pot.getQuantity()).thenReturn((short) 1);
        when(pot.getPosition()).thenReturn((short) 4);
        when(use.findById(cfg.mp_pot_item_id)).thenReturn(pot);

        server.StatEffect effect = mock(server.StatEffect.class);
        when(effect.getMp()).thenReturn((short) 300);

        MapActuator a = new MapActuator(cfg, id -> null, id -> effect);
        a.useMpPot(b);

        verify(chr).addMP(300);
        verify(use).removeItem((short) 4);
    }

    @Test
    void usePotDoesNothingWhenInventoryMissing() {
        BotConfig cfg = new BotConfig();
        Bot b = bot(-1_000_000);
        Character chr = b.character();
        when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(null);

        MapActuator a = new MapActuator(cfg, id -> null, id -> null);
        a.useHpPot(b); // should not throw
        verify(chr, never()).addHP(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void pickupCallsMapPickItemDropForNearestDrop() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);

        server.maps.MapItem near = mock(server.maps.MapItem.class);
        when(near.getPosition()).thenReturn(new Point(10, 0));
        when(near.getObjectId()).thenReturn(99);
        server.maps.MapItem far = mock(server.maps.MapItem.class);
        when(far.getPosition()).thenReturn(new Point(80, 0));
        when(far.getObjectId()).thenReturn(100);
        when(map.getMapObjects()).thenReturn(java.util.List.of(far, near));

        a.pickup(b);
        verify(map).pickItemDrop(any(), same(near));
    }

    @Test
    void pickupSkipsDropsOutsideRadius() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);

        server.maps.MapItem outOfRange = mock(server.maps.MapItem.class);
        when(outOfRange.getPosition()).thenReturn(new Point(500, 0));
        when(map.getMapObjects()).thenReturn(java.util.List.of(outOfRange));

        a.pickup(b);
        verify(map, never()).pickItemDrop(any(), any());
    }

    @Test
    void pickupNoOpWhenMapIsNull() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getMap()).thenReturn(null);
        a.pickup(b); // should not throw
    }

    @Test
    void scheduleReviveTopsUpHpAndMpOnFire() {
        BotConfig cfg = new BotConfig();
        cfg.revive_delay_ms = 100;
        Runnable[] captured = {null};
        long[] capturedDelay = {-1};
        MapActuator a = new MapActuator(cfg,
                id -> null,
                id -> null,
                (r, ms) -> { captured[0] = r; capturedDelay[0] = ms; });

        Bot b = bot(-1_000_000);
        Character chr = b.character();
        when(chr.getMaxHp()).thenReturn(1500);
        when(chr.getMaxMp()).thenReturn(200);
        when(chr.getHp()).thenReturn(0);
        when(chr.getMp()).thenReturn(50);
        // No map/client stubbed: spawn-broadcast block is skipped (or its
        // packet build NPEs and is swallowed). The HP/MP top-up runs first
        // and is what we assert on.

        a.scheduleRevive(b, cfg.revive_delay_ms);
        assertNotNull(captured[0]);
        assertEquals(100L, capturedDelay[0]);
        captured[0].run();

        verify(chr).addHP(1500);
        verify(chr).addMP(150);
    }

    @Test
    void scheduleReviveSkipsBroadcastWhenMapMissing() {
        BotConfig cfg = new BotConfig();
        Runnable[] captured = {null};
        MapActuator a = new MapActuator(cfg,
                id -> null,
                id -> null,
                (r, ms) -> captured[0] = r);

        Bot b = bot(-1_000_000);
        Character chr = b.character();
        when(chr.getMaxHp()).thenReturn(1000);
        when(chr.getMaxMp()).thenReturn(100);
        when(chr.getHp()).thenReturn(1000); // already full
        when(chr.getMp()).thenReturn(100);
        when(chr.getMap()).thenReturn(null);

        a.scheduleRevive(b, 50);
        captured[0].run(); // should not throw

        verify(chr, never()).addHP(org.mockito.ArgumentMatchers.anyInt());
        verify(chr, never()).addMP(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void acceptPartyInviteIsNoOpWhenNoInviteRegistered() {
        // Static methods (InviteCoordinator.peekInviterId) are awkward to mock without
        // mockito-inline. The impl is tolerant: if peekInviterId throws or returns null
        // (no invite registered), it's a no-op. Verify the actuator does NOT call the
        // partyJoiner in that path.
        int[] joinerCalls = {0};
        MapActuator a = new MapActuator(new BotConfig(),
                id -> null,
                id -> null,
                (r, ms) -> {},
                (chr, partyId) -> { joinerCalls[0]++; return true; });
        Bot b = bot(-1_000_000);
        a.acceptPartyInvite(b);
        assertEquals(0, joinerCalls[0]);
    }

    @Test
    void usePotDoesNothingWhenEffectLookupReturnsNull() {
        BotConfig cfg = new BotConfig();
        Bot b = bot(-1_000_000);
        Character chr = b.character();
        client.inventory.Inventory use = mock(client.inventory.Inventory.class);
        when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
        client.inventory.Item pot = mock(client.inventory.Item.class);
        when(pot.getItemId()).thenReturn(cfg.hp_pot_item_id);
        when(use.findById(cfg.hp_pot_item_id)).thenReturn(pot);

        MapActuator a = new MapActuator(cfg, id -> null, id -> null);
        a.useHpPot(b); // should not throw
        verify(chr, never()).addHP(org.mockito.ArgumentMatchers.anyInt());
        verify(pot, never()).setQuantity(org.mockito.ArgumentMatchers.anyShort());
    }
}
