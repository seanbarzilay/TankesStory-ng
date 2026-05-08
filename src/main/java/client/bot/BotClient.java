package client.bot;

import client.Client;
import net.packet.Packet;
import server.bot.Bot;
import server.bot.BotManager;

/**
 * A {@link Client} that represents an in-process bot rather than a real
 * network connection.  All outbound packet writes are silently discarded,
 * and the Netty-touching methods ({@code closeSession}, {@code disconnectSession},
 * {@code checkIfIdle}) are no-ops so that a null {@code ioChannel} never
 * causes a NullPointerException.
 *
 * <p>Because {@link Client#disconnect(boolean, boolean)} is {@code final} and
 * schedules its teardown asynchronously through Netty / SessionCoordinator
 * infrastructure that bots don't own, callers should use {@link #botDisconnect()}
 * instead to cleanly unregister the bot from its {@link BotManager}.
 */
public class BotClient extends Client {

    private Bot bot;
    private BotManager manager;

    public BotClient(int world, int channel) {
        super(Type.CHANNEL, /*sessionId=*/-1L, /*remoteAddress=*/"bot",
              /*packetProcessor=*/null, world, channel);
    }

    public void attachBot(Bot bot, BotManager manager) {
        this.bot = bot;
        this.manager = manager;
    }

    /**
     * Bot-specific teardown: unregisters the bot from the manager.
     * Use this instead of {@link #disconnect(boolean, boolean)}, which is
     * {@code final} in {@link Client} and requires a live Netty/Server stack.
     */
    public void botDisconnect() {
        if (manager != null && bot != null) {
            manager.unregister(bot);
        }
    }

    // -------------------------------------------------------------------------
    // Outbound packet — discard silently; bots have no Netty channel.
    // -------------------------------------------------------------------------

    @Override
    public void sendPacket(Packet packet) {
        // Bots do not render their own view; drop all outbound packets.
    }

    // -------------------------------------------------------------------------
    // Address identity
    // -------------------------------------------------------------------------

    @Override
    public String getRemoteAddress() {
        return "bot";
    }

    // -------------------------------------------------------------------------
    // Netty channel guards — ioChannel is null for bots (never went through
    // channelActive), so these must not delegate to the parent implementation.
    // -------------------------------------------------------------------------

    @Override
    public void closeSession() {
        // No Netty channel; nothing to close.
    }

    @Override
    public void disconnectSession() {
        // No Netty channel; nothing to disconnect.
    }
}
