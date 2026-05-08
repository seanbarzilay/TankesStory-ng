package server.bot;

import config.BotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BotManager {

    public static class AtCapException extends RuntimeException {
        public AtCapException(int world, int cap) {
            super("bots.max_per_world (" + cap + ") reached for world " + world);
        }
    }

    private final BotConfig cfg;
    private final Map<Integer, Bot> byId = new ConcurrentHashMap<>();

    public BotManager(BotConfig cfg) {
        this.cfg = cfg;
    }

    public synchronized void register(Bot bot) {
        long inWorld = byId.values().stream().filter(b -> b.world() == bot.world()).count();
        if (inWorld >= cfg.max_per_world) {
            throw new AtCapException(bot.world(), cfg.max_per_world);
        }
        byId.put(bot.id(), bot);
    }

    public void unregister(Bot bot) {
        byId.remove(bot.id());
    }

    public Bot findById(int id) {
        return byId.get(id);
    }

    public Bot findByName(String name) {
        for (Bot b : byId.values()) {
            if (b.name().equals(name)) return b;
        }
        return null;
    }

    public List<Bot> listInWorld(int world) {
        List<Bot> out = new ArrayList<>();
        for (Bot b : byId.values()) {
            if (b.world() == world) out.add(b);
        }
        return out;
    }

    public List<Bot> activeBots() {
        return new ArrayList<>(byId.values());
    }

    public boolean isBotName(String name) {
        return findByName(name) != null;
    }
}
