package client.command.commands.gm1;

import client.Client;
import client.bot.BotFactory;
import client.bot.BotPreset;
import client.command.Command;
import server.bot.Bot;
import server.bot.BotManager;

public class BotCommand extends Command {

    {
        setDescription("Spawn / drive player-bots. Subcommands: spawn, follow <name>, grind [filter], stop, despawn <name>, list.");
    }

    private BotFactory factory;
    private BotManager manager;

    public BotCommand() {
        this(null, null);
    }

    public BotCommand(BotFactory factory, BotManager manager) {
        this.factory = factory;
        this.manager = manager;
    }

    /**
     * Wired once at server boot (Task 19). Must run before the first @bot
     * invocation, but may run AFTER {@code CommandsExecutor} caches the no-arg
     * instance — the instance reads {@link Holder} lazily on each execute().
     */
    public static void wire(BotFactory f, BotManager m) {
        Holder.factory = f;
        Holder.manager = m;
    }

    private static class Holder {
        static BotFactory factory;
        static BotManager manager;
    }

    @Override
    public void execute(Client c, String[] params) {
        if (factory == null) factory = Holder.factory;
        if (manager == null) manager = Holder.manager;
        if (factory == null || manager == null) {
            c.getPlayer().dropMessage(1, "bots disabled (not wired)");
            return;
        }
        if (params.length == 0) {
            c.getPlayer().dropMessage(1, "usage: @bot spawn|follow <name>|grind [filter]|stop|despawn <name>|list");
            return;
        }
        switch (params[0]) {
            case "spawn"   -> spawn(c);
            case "follow"  -> follow(c, params);
            case "grind"   -> grind(c, params);
            case "stop"    -> stop(c, params);
            case "despawn" -> despawn(c, params);
            case "list"    -> list(c);
            default -> c.getPlayer().dropMessage(1, "unknown subcommand: " + params[0]);
        }
    }

    private void spawn(Client c) {
        var p = c.getPlayer();
        try {
            Bot bot = factory.spawn(p.getWorld(), c.getChannel(), p.getMapId(),
                    p.getPosition().x, p.getPosition().y, BotPreset.BEGINNER_LV30);
            p.dropMessage(5, "spawned " + bot.name() + " (id=" + bot.id() + ")");
        } catch (BotFactory.DisabledException e) {
            p.dropMessage(1, "bots disabled: set bots.enabled: true in config.yaml");
        } catch (BotManager.AtCapException e) {
            p.dropMessage(1, e.getMessage());
        }
    }

    private void follow(Client c, String[] params) {
        client.Character target = c.getPlayer();
        if (params.length >= 2) {
            client.Character p = c.getChannelServer().getPlayerStorage().getCharacterByName(params[1]);
            if (p == null) {
                c.getPlayer().dropMessage(1, "no player named " + params[1]);
                return;
            }
            target = p;
        }
        var bots = manager.listInWorld(c.getPlayer().getWorld());
        if (bots.isEmpty()) {
            c.getPlayer().dropMessage(1, "no bots in your world; spawn one with @bot spawn");
            return;
        }
        Bot bot = bots.get(0);
        bot.setMode(Bot.Mode.FOLLOW);
        bot.setTargetCharId(target.getId());
        c.getPlayer().dropMessage(5, bot.name() + " is now following " + target.getName());
    }

    private void grind(Client c, String[] params) {
        var bots = manager.listInWorld(c.getPlayer().getWorld());
        if (bots.isEmpty()) { c.getPlayer().dropMessage(1, "no bots in your world"); return; }
        Bot bot = bots.get(0);
        bot.setMode(Bot.Mode.GRIND);
        if (params.length >= 2) bot.setMobFilter(params[1]);
        c.getPlayer().dropMessage(5, bot.name() + " is now grinding");
    }

    private void stop(Client c, String[] params) {
        if (params.length >= 2) {
            Bot b = manager.findByName(params[1]);
            if (b != null) b.setMode(Bot.Mode.IDLE);
        } else {
            for (Bot b : manager.listInWorld(c.getPlayer().getWorld())) b.setMode(Bot.Mode.IDLE);
        }
        c.getPlayer().dropMessage(5, "stopped");
    }

    private void despawn(Client c, String[] params) {
        if (params.length < 2) { c.getPlayer().dropMessage(1, "usage: @bot despawn <bot-name>"); return; }
        Bot bot = manager.findByName(params[1]);
        if (bot == null) { c.getPlayer().dropMessage(1, "no bot named " + params[1]); return; }
        factory.despawn(bot);
        c.getPlayer().dropMessage(5, "despawned " + params[1]);
    }

    private void list(Client c) {
        var bots = manager.listInWorld(c.getPlayer().getWorld());
        if (bots.isEmpty()) { c.getPlayer().dropMessage(5, "no bots in this world"); return; }
        StringBuilder s = new StringBuilder("bots in this world: ");
        for (Bot b : bots) s.append(b.name()).append("(").append(b.mode()).append(") ");
        c.getPlayer().dropMessage(5, s.toString());
    }
}
