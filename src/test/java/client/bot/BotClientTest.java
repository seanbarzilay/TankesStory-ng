package client.bot;

import config.BotConfig;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.Bot;
import server.bot.BotManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotClientTest {

    @Test
    void sendPacketIsNoOp() {
        BotClient c = new BotClient(0, 0);
        // Should not throw despite no Netty channel.
        c.sendPacket(Mockito.mock(Packet.class));
    }

    @Test
    void getRemoteAddressIsBotSentinel() {
        BotClient c = new BotClient(0, 0);
        assertEquals("bot", c.getRemoteAddress());
    }

    @Test
    void disconnectRoutesToBotManager() {
        BotManager mgr = new BotManager(new BotConfig());
        Bot bot = mock(Bot.class);
        when(bot.id()).thenReturn(-1_000_000);
        when(bot.world()).thenReturn(0);
        mgr.register(bot);
        BotClient c = new BotClient(0, 0);
        c.attachBot(bot, mgr);
        // botDisconnect() is the BotClient-specific teardown path: it unregisters
        // the bot from the manager without going through the Netty/Server
        // infrastructure that Client.disconnect (final) requires.
        c.botDisconnect();
        assertNull(mgr.findById(-1_000_000));
    }
}
