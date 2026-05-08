package server.bot;

import client.Character;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BotManagerTest {

    private static Bot fakeBot(int id, String name, int world) {
        Character chr = Mocks.chr(name);
        when(chr.getId()).thenReturn(id);
        when(chr.getWorld()).thenReturn(world);
        return new Bot(chr);
    }

    @Test
    void registerAndLookupById() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        assertSame(b, m.findById(-1_000_000));
    }

    @Test
    void registerAndLookupByName() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        assertSame(b, m.findByName("Bot01"));
        assertNull(m.findByName("notabot"));
    }

    @Test
    void unregisterRemoves() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        m.unregister(b);
        assertNull(m.findById(-1_000_000));
    }

    @Test
    void listInWorldReturnsOnlyMatching() {
        BotManager m = new BotManager(new BotConfig());
        m.register(fakeBot(-1_000_000, "A", 0));
        m.register(fakeBot(-1_000_001, "B", 0));
        m.register(fakeBot(-1_000_002, "C", 1));
        assertEquals(2, m.listInWorld(0).size());
        assertEquals(1, m.listInWorld(1).size());
    }

    @Test
    void enforcesMaxPerWorld() {
        BotConfig cfg = new BotConfig();
        cfg.max_per_world = 2;
        BotManager m = new BotManager(cfg);
        m.register(fakeBot(-1_000_000, "A", 0));
        m.register(fakeBot(-1_000_001, "B", 0));
        assertThrows(BotManager.AtCapException.class,
                () -> m.register(fakeBot(-1_000_002, "C", 0)));
    }

    @Test
    void activeBotsReturnsSnapshotIndependentOfMutation() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "A", 0);
        m.register(b);
        var snapshot = m.activeBots();
        m.unregister(b);
        assertEquals(1, snapshot.size(), "snapshot must be independent of registry");
    }
}
