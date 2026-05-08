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
}
