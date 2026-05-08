package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.bot.BotFactory;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotCommandTest {

    @Test
    void despawnRemovesBotByName() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(-1_000_000);
        when(chr.getWorld()).thenReturn(0);
        when(chr.getMapId()).thenReturn(100000000);
        Bot bot = new Bot(chr);
        mgr.register(bot);

        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(),
                (a,b,c,d)->{}, (a,b)->{});

        Character gm = Mocks.chr("GM");
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);

        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"despawn", "Bot01"});
        assertNull(mgr.findByName("Bot01"));
    }

    @Test
    void followSetsModeAndTarget() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character bchr = Mocks.chr("Bot01");
        when(bchr.getId()).thenReturn(-1_000_000);
        when(bchr.getWorld()).thenReturn(0);
        Bot bot = new Bot(bchr);
        mgr.register(bot);
        Character gm = Mocks.chr("GM");
        when(gm.getId()).thenReturn(99);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow", "Bot01"});
        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(99, bot.targetCharId());
    }

    @Test
    void unknownSubcommandReportsToPlayer() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        Character gm = Mocks.chr("GM");
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"flarble"});
        verify(gm).dropMessage(eq(1), contains("unknown subcommand"));
    }
}
