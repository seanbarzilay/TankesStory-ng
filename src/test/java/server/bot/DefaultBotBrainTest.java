package server.bot;

import client.Character;
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
}
