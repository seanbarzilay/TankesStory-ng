package client.bot;

import client.Character;
import config.BotConfig;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;

import java.util.concurrent.atomic.AtomicInteger;

public class BotFactory {

    public static class DisabledException extends RuntimeException {
        public DisabledException() { super("bots.enabled is false"); }
    }

    public interface Placer {
        void placeOnMap(Character chr, int mapId, int x, int y);
    }

    public interface Remover {
        void removeFromMap(Character chr, int mapId);
    }

    private final BotConfig cfg;
    private final BotManager manager;
    private final BotIdAllocator ids;
    private final Placer placer;
    private final Remover remover;
    private final AtomicInteger nameSequence = new AtomicInteger(0);

    public BotFactory(BotConfig cfg, BotManager manager, BotIdAllocator ids, Placer placer) {
        this(cfg, manager, ids, placer, (chr, mapId) -> {});
    }

    public BotFactory(BotConfig cfg, BotManager manager, BotIdAllocator ids,
                      Placer placer, Remover remover) {
        this.cfg = cfg;
        this.manager = manager;
        this.ids = ids;
        this.placer = placer;
        this.remover = remover;
    }

    public Bot spawn(int world, int channel, int mapId, int x, int y, BotPreset preset) {
        if (!cfg.enabled) throw new DisabledException();

        int id = ids.next();
        String name = String.format("%s%02d", cfg.name_prefix, nameSequence.incrementAndGet());

        // The exact construction call is documented in
        // docs/superpowers/notes/2026-05-08-player-bot-investigation.md section B.
        // Task 19 replaces BotCharacterFactory.create body with the real call.
        Character chr = BotCharacterFactory.create(world, channel, id, name, preset);

        Bot bot = new Bot(chr);
        manager.register(bot);
        placer.placeOnMap(chr, mapId, x, y);
        return bot;
    }

    public void despawn(Bot bot) {
        Character chr = bot.character();
        remover.removeFromMap(chr, chr.getMapId());
        manager.unregister(bot);
    }
}
