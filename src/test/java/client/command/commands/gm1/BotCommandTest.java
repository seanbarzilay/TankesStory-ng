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
    void followNoArgsTargetsTheCallingGm() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character bchr = Mocks.chr("Bot01");
        when(bchr.getId()).thenReturn(-1_000_000);
        when(bchr.getWorld()).thenReturn(0);
        Bot bot = new Bot(bchr);
        mgr.register(bot);
        Character gm = Mocks.chr("GM");
        when(gm.getId()).thenReturn(99);
        when(gm.getWorld()).thenReturn(0);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow"});
        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(99, bot.targetCharId(), "default target is the GM running the command");
    }

    @Test
    void followWithPlayerNameLooksUpThatPlayer() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character bchr = Mocks.chr("Bot01");
        when(bchr.getId()).thenReturn(-1_000_000);
        when(bchr.getWorld()).thenReturn(0);
        Bot bot = new Bot(bchr);
        mgr.register(bot);
        Character gm = Mocks.chr("GM");
        when(gm.getId()).thenReturn(99);
        when(gm.getWorld()).thenReturn(0);
        Character target = Mocks.chr("Bob");
        when(target.getId()).thenReturn(7);

        net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
        when(storage.getCharacterByName("Bob")).thenReturn(target);
        net.server.channel.Channel channel = mock(net.server.channel.Channel.class);
        when(channel.getPlayerStorage()).thenReturn(storage);

        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        when(c.getChannelServer()).thenReturn(channel);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow", "Bob"});
        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(7, bot.targetCharId());
    }

    @Test
    void followWithUnknownPlayerNameReportsError() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character bchr = Mocks.chr("Bot01");
        when(bchr.getId()).thenReturn(-1_000_000);
        when(bchr.getWorld()).thenReturn(0);
        mgr.register(new Bot(bchr));
        Character gm = Mocks.chr("GM");
        when(gm.getWorld()).thenReturn(0);

        net.server.PlayerStorage storage = mock(net.server.PlayerStorage.class);
        when(storage.getCharacterByName("Nobody")).thenReturn(null);
        net.server.channel.Channel channel = mock(net.server.channel.Channel.class);
        when(channel.getPlayerStorage()).thenReturn(storage);

        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        when(c.getChannelServer()).thenReturn(channel);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow", "Nobody"});
        verify(gm).dropMessage(eq(1), contains("no player named"));
    }

    @Test
    void noArgInstanceReadsHolderLazilyAfterWire() {
        // Reproduces the production bug: CommandsExecutor caches a no-arg
        // BotCommand instance at startup, BEFORE Server.init wires the bot
        // subsystem. The cached instance must still pick up the wiring on
        // its first execute() call, not capture nulls at construction.
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Character bchr = Mocks.chr("Bot01");
        when(bchr.getId()).thenReturn(-1_000_000);
        when(bchr.getWorld()).thenReturn(0);
        mgr.register(new Bot(bchr));
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});

        // Construct via no-arg ctor BEFORE wire() (mirrors CommandsExecutor's reflective addCommand).
        BotCommand cmd = new BotCommand();
        // Then wire (mirrors Server.init's later wiring).
        BotCommand.wire(factory, mgr);

        Character gm = Mocks.chr("GM");
        when(gm.getId()).thenReturn(99);
        when(gm.getWorld()).thenReturn(0);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        cmd.execute(c, new String[]{"follow"});
        assertEquals(Bot.Mode.FOLLOW, mgr.findByName("Bot01").mode(),
                "no-arg instance constructed pre-wire should still read wired deps at execute time");
    }

    @Test
    void multipleBotsCommandsAddressOwnedBot() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);

        Character bot1Chr = Mocks.chr("Bot01");
        when(bot1Chr.getId()).thenReturn(-1_000_000);
        when(bot1Chr.getWorld()).thenReturn(0);
        Bot bot1 = new Bot(bot1Chr);
        bot1.setSpawnerCharId(11);
        mgr.register(bot1);

        Character bot2Chr = Mocks.chr("Bot02");
        when(bot2Chr.getId()).thenReturn(-1_000_001);
        when(bot2Chr.getWorld()).thenReturn(0);
        Bot bot2 = new Bot(bot2Chr);
        bot2.setSpawnerCharId(22);
        mgr.register(bot2);

        Character gm22 = Mocks.chr("GM22");
        when(gm22.getId()).thenReturn(22);
        when(gm22.getWorld()).thenReturn(0);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm22);

        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow"});
        assertEquals(Bot.Mode.FOLLOW, bot2.mode(), "GM22's bot should flip to FOLLOW");
        assertEquals(Bot.Mode.IDLE, bot1.mode(), "Other GM's bot must NOT be touched");
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
