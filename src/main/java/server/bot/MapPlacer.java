package server.bot;

import client.Character;
import client.bot.BotFactory;
import net.server.Server;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * Production {@link BotFactory.Placer}/{@link BotFactory.Remover} that
 * resolves a {@link MapleMap} via {@code Server.getInstance()} and
 * registers/unregisters the bot's {@link Character} on it.
 */
public class MapPlacer implements BotFactory.Placer, BotFactory.Remover {

    @Override
    public void placeOnMap(Character chr, int mapId, int x, int y) {
        MapleMap map = mapFor(chr.getWorld(), chr.getClient().getChannel(), mapId);
        if (map == null) return;
        chr.setMap(map);
        chr.setPosition(new Point(x, y));
        map.addPlayer(chr);
    }

    @Override
    public void removeFromMap(Character chr, int mapId) {
        MapleMap map = mapFor(chr.getWorld(), chr.getClient().getChannel(), mapId);
        if (map == null) return;
        map.removePlayer(chr);
    }

    private static MapleMap mapFor(int world, int channel, int mapId) {
        try {
            return Server.getInstance().getWorld(world)
                    .getChannel(channel).getMapFactory().getMap(mapId);
        } catch (Throwable t) {
            return null;
        }
    }
}
