package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import net.server.chat.telegram.RateLimiter;
import net.server.chat.telegram.TelegramBridgeService;
import net.server.chat.telegram.WorldChatService;

public class WorldCommand extends Command {

    {
        setDescription("Send a message to world chat (bridged to Telegram).");
    }

    @Override
    public void execute(Client client, String[] params) {
        TelegramBridgeService.instance().ifPresent(svc -> {
            String text = client.getPlayer().getLastCommandMessage();
            if (text == null) return;
            text = text.strip();
            if (text.isEmpty()) return;
            deliver(svc.worldChat(), svc.rateLimiter(),
                    client.getWorld(), client.getPlayer().getId(),
                    client.getPlayer().getName(), text);
        });
    }

    public static void deliver(WorldChatService svc, RateLimiter rl,
                               int worldId, int charId, String charName, String text) {
        if (text == null || text.strip().isEmpty()) return;
        if (!rl.tryAcquire(charId)) return;
        svc.send(worldId, charName, text);
    }
}
