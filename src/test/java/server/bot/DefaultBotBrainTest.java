package server.bot;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class DefaultBotBrainTest {

    static Bot aliveBot() {
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(-1_000_000);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getHp()).thenReturn(1000);
        when(chr.getMaxHp()).thenReturn(1000);
        when(chr.getMp()).thenReturn(200);
        when(chr.getMaxMp()).thenReturn(200);
        return new Bot(chr);
    }

    @Test
    void modeIdleReturnsIdle() {
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.IDLE, b.decide(aliveBot(), 0L));
    }

    @Test
    void lowHpWithPotUsesPot() {
        Bot bot = aliveBot();
        Character chr = bot.character();
        when(chr.getHp()).thenReturn(100);
        Inventory use = org.mockito.Mockito.mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        Item pot = org.mockito.Mockito.mock(Item.class);
        when(pot.getItemId()).thenReturn(2000000);
        when(use.findById(2000000)).thenReturn(pot);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.USE_HP_POT, b.decide(bot, 0L));
    }

    @Test
    void lowHpWithoutPotRetreats() {
        Bot bot = aliveBot();
        Character chr = bot.character();
        when(chr.getHp()).thenReturn(100);
        Inventory use = org.mockito.Mockito.mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        when(use.findById(2000000)).thenReturn(null);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.RETREAT, b.decide(bot, 0L));
    }

    @Test
    void lowMpWithPotUsesMpPot() {
        Bot bot = aliveBot();
        Character chr = bot.character();
        when(chr.getMp()).thenReturn(20);
        Inventory use = org.mockito.Mockito.mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(use);
        Item pot = org.mockito.Mockito.mock(Item.class);
        when(pot.getItemId()).thenReturn(2000003);
        when(use.findById(2000003)).thenReturn(pot);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.USE_MP_POT, b.decide(bot, 0L));
    }

    @Test
    void hpAboveThresholdDoesNotUsePot() {
        Bot bot = aliveBot();
        Character chr = bot.character();
        when(chr.getHp()).thenReturn(900);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.IDLE, b.decide(bot, 0L));
    }

    @Test
    void deadBotReturnsWaitRevive() {
        Bot bot = aliveBot();
        Character chr = bot.character();
        when(chr.isAlive()).thenReturn(false);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.WAIT_REVIVE, b.decide(bot, 0L));
    }

    private static Bot followingBot(int targetId) {
        Bot bot = aliveBot();
        bot.setMode(Bot.Mode.FOLLOW);
        bot.setTargetCharId(targetId);
        return bot;
    }

    @Test
    void followTargetSameMapInRadiusIdles() {
        Bot bot = followingBot(123);
        Character target = Mocks.chr("Player");
        when(target.getMapId()).thenReturn(100000000);
        when(target.getPosition()).thenReturn(new java.awt.Point(0, 0));
        when(bot.character().getMapId()).thenReturn(100000000);
        when(bot.character().getPosition()).thenReturn(new java.awt.Point(50, 0));
        FakeWorldView w = new FakeWorldView();
        w.chars.put(123, target);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.IDLE, b.decide(bot, 0L));
    }

    @Test
    void followTargetSameMapOutOfRadiusSteps() {
        Bot bot = followingBot(123);
        Character target = Mocks.chr("Player");
        when(target.getMapId()).thenReturn(100000000);
        when(target.getPosition()).thenReturn(new java.awt.Point(500, 0));
        when(bot.character().getMapId()).thenReturn(100000000);
        when(bot.character().getPosition()).thenReturn(new java.awt.Point(0, 0));
        FakeWorldView w = new FakeWorldView();
        w.chars.put(123, target);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.STEP_TOWARD_TARGET, b.decide(bot, 0L));
    }

    @Test
    void followTargetOnDifferentMapWalksToPortal() {
        Bot bot = followingBot(123);
        Character target = Mocks.chr("Player");
        when(target.getMapId()).thenReturn(100000001);
        when(bot.character().getMapId()).thenReturn(100000000);
        FakeWorldView w = new FakeWorldView();
        w.chars.put(123, target);
        w.nearestPortalToTarget = 5;
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.WALK_TO_PORTAL, b.decide(bot, 0L));
    }

    @Test
    void followTargetGoneFallsBackToIdle() {
        Bot bot = followingBot(123);
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.IDLE, b.decide(bot, 0L));
        assertNull(bot.targetCharId());
    }

    private static Bot grindingBot() {
        Bot bot = aliveBot();
        bot.setMode(Bot.Mode.GRIND);
        when(bot.character().getMapId()).thenReturn(100000000);
        when(bot.character().getPosition()).thenReturn(new java.awt.Point(0, 0));
        return bot;
    }

    @Test
    void grindNoMobsIdles() {
        Bot bot = grindingBot();
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.IDLE, b.decide(bot, 0L));
    }

    @Test
    void grindMobOutOfRangeSteps() {
        Bot bot = grindingBot();
        FakeWorldView w = new FakeWorldView();
        w.nearbyMobs = java.util.List.of(101);
        w.inRange = false;
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.STEP_TOWARD_MOB, b.decide(bot, 0L));
    }

    @Test
    void grindMeleeAttacks() {
        Bot bot = grindingBot();
        FakeWorldView w = new FakeWorldView();
        w.nearbyMobs = java.util.List.of(101);
        w.inRange = true;
        w.ranged = false;
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.ATTACK_MELEE, b.decide(bot, 0L));
    }

    @Test
    void grindRangedAttacks() {
        Bot bot = grindingBot();
        FakeWorldView w = new FakeWorldView();
        w.nearbyMobs = java.util.List.of(101);
        w.inRange = true;
        w.ranged = true;
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
        assertEquals(BotAction.ATTACK_RANGED, b.decide(bot, 0L));
    }
}
