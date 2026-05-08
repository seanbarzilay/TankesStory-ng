package client.bot;

import config.BotConfig;
import org.junit.jupiter.api.Test;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;
import static org.junit.jupiter.api.Assertions.*;

class BotFactoryTest {

    @Test
    void spawnDisabledByConfigThrows() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = false;
        BotFactory factory = new BotFactory(cfg, new BotManager(cfg),
                new BotIdAllocator(), (chr,m,x,y)->{});
        assertThrows(BotFactory.DisabledException.class,
                () -> factory.spawn(0, 0, 100000000, 0, 0, BotPreset.BEGINNER_LV30));
    }

    @Test
    void despawnUnregistersAndCallsRemover() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        // Use a Bot built directly (Task 4 pattern) so we don't trigger
        // the Character-construction placeholder that Task 19 fills in.
        client.Character chr = testutil.Mocks.chr("Bot01");
        org.mockito.Mockito.when(chr.getId()).thenReturn(-1_000_000);
        org.mockito.Mockito.when(chr.getWorld()).thenReturn(0);
        org.mockito.Mockito.when(chr.getMapId()).thenReturn(100000000);
        Bot bot = new Bot(chr);
        mgr.register(bot);

        int[] removerCalled = {0};
        BotFactory.Remover remover = (c, mapId) -> removerCalled[0]++;
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(),
                (c,m,x,y) -> {}, remover);
        factory.despawn(bot);
        assertEquals(1, removerCalled[0]);
        assertNull(mgr.findById(-1_000_000));
    }
}
