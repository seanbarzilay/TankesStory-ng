package server.bot;

import client.Character;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BotSchedulerTest {

    private static Bot fakeBot(int id) {
        Character chr = Mocks.chr("Bot" + id);
        when(chr.getId()).thenReturn(id);
        when(chr.getWorld()).thenReturn(0);
        return new Bot(chr);
    }

    @Test
    void runOnceTicksAllBots() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        Bot b = fakeBot(-1_000_001);
        m.register(a); m.register(b);
        int[] ticks = {0};
        BotBrain brain = (bot, now) -> ticks[0]++;
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(123L);
        assertEquals(2, ticks[0]);
    }

    @Test
    void brainExceptionDoesNotKillLoop() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        Bot b = fakeBot(-1_000_001);
        m.register(a); m.register(b);
        int[] ticks = {0};
        BotBrain brain = (bot, now) -> {
            ticks[0]++;
            if (bot.id() == -1_000_000) throw new RuntimeException("boom");
        };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L);
        s.runOnce(2L);
        assertEquals(4, ticks[0], "both bots ticked twice despite one bot's exception");
    }

    @Test
    void threeConsecutiveFailuresDespawnsBot() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        m.register(a);
        BotBrain brain = (bot, now) -> { throw new RuntimeException("boom"); };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L);
        s.runOnce(2L);
        assertNotNull(m.findById(-1_000_000), "still alive after 2 failures");
        s.runOnce(3L);
        assertNull(m.findById(-1_000_000), "auto-despawned after 3 failures");
    }

    @Test
    void successResetsFailureCounter() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        m.register(a);
        int[] failNext = {2};
        BotBrain brain = (bot, now) -> {
            if (failNext[0] > 0) { failNext[0]--; throw new RuntimeException("boom"); }
        };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L); s.runOnce(2L);
        s.runOnce(3L);
        failNext[0] = 2;
        s.runOnce(4L); s.runOnce(5L);
        assertNotNull(m.findById(-1_000_000), "should still be alive (no 3-in-a-row run)");
    }
}
