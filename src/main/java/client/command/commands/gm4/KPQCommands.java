package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQBot;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQConstants;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQOrchestrator;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQSharedContext;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GM tools for Kerning PQ bots.
 * Usage: !kpq help | status | start | reset | list | spawn &lt;n&gt; | killall | warp lobby
 */
public class KPQCommands extends Command {

    {
        setDescription("Kerning PQ bot orchestration (!kpq help)");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            printHelp(player);
            return;
        }
        String cmd = params[0].toLowerCase();
        ExecutorServiceManager.getExecutorService().execute(() -> handle(player, cmd, params));
    }

    private void handle(Character player, String cmd, String[] params) {
        try {
            switch (cmd) {
                case "help" -> printHelp(player);
                case "status" -> printStatus(player);
                case "start" -> {
                    KPQOrchestrator.getInstance().resetForNewRun();
                    player.yellowMessage("KPQ run set to RECRUITMENT.");
                }
                case "reset" -> {
                    KPQOrchestrator.getInstance().shutdownRun();
                    player.yellowMessage("KPQ run shut down.");
                }
                case "list" -> listBots(player);
                case "spawn" -> {
                    int n = params.length > 1 ? Integer.parseInt(params[1]) : 3;
                    spawnAtPlayer(player, n);
                }
                case "killall" -> {
                    int k = killAll();
                    player.yellowMessage("Stopped " + k + " KPQ bots.");
                }
                case "warp" -> {
                    if (params.length < 2 || !params[1].equalsIgnoreCase("lobby")) {
                        player.yellowMessage("Usage: !kpq warp lobby");
                        return;
                    }
                    var map = player.getClient().getChannelServer().getMapFactory()
                            .getMap(KPQConstants.KPQ_LOBBY);
                    player.changeMap(map, map.getPortal(0));
                    player.yellowMessage("Warped to Kerning KPQ lobby.");
                }
                case "spawnlobby" -> {
                    EnvironmentManager.spawnKPQBotsInLobby();
                    player.yellowMessage("spawnKPQBotsInLobby() invoked.");
                }
                default -> player.yellowMessage("Unknown !kpq command. Try !kpq help");
            }
        } catch (Exception e) {
            player.yellowMessage("!kpq error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printHelp(Character player) {
        player.yellowMessage("---- !kpq (Kerning PQ bots) ----");
        player.yellowMessage("!kpq help | status | start | reset | list");
        player.yellowMessage("!kpq spawn <n>     - spawn n KPQ bots at your feet");
        player.yellowMessage("!kpq spawnlobby    - spawn KPQ bots in Kerning City");
        player.yellowMessage("!kpq killall       - stop all KPQ bots");
        player.yellowMessage("!kpq warp lobby    - warp yourself to Kerning City");
        player.yellowMessage("Flow: invite KPQ bots (auto-accept) -> Lakelis start PQ -> bots follow.");
    }

    private static void printStatus(Character player) {
        KPQOrchestrator orch = KPQOrchestrator.getInstance();
        KPQSharedContext ctx = orch.getSharedContext();
        player.yellowMessage("KPQ phase=" + ctx.getCurrentPhase()
                + " active=" + ctx.isPqActive()
                + " leaderId=" + orch.getLeaderId()
                + " leaderMap=" + ctx.getLeaderMapId()
                + " bots=" + orch.getRegisteredBots().size());
    }

    private static void listBots(Character player) {
        player.yellowMessage("---- KPQ bots ----");
        for (KPQBot bot : snapshot()) {
            Character ch = bot.getChr();
            player.yellowMessage("cid=" + ch.getId()
                    + " name=" + ch.getName()
                    + " lv=" + ch.getLevel()
                    + " map=" + ch.getMapId()
                    + " state=" + bot.getKPQBotState()
                    + " party=" + (ch.getParty() != null));
        }
    }

    private static void spawnAtPlayer(Character player, int n) {
        int spawned = 0;
        Point base = player.getPosition();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point p = new Point(base.x + i * 30, base.y);
            int id = BotGeneration.createBot(p, player.getMap(), 1, 21, 40);
            Character fake = soloMapling.ArtificialPlayer.BotHelpers.getCharFromChannelStorage(id);
            if (fake == null) {
                continue;
            }
            fake.setLevel(21 + (i % 20));
            ids.add(id);
            spawned++;
        }
        if (!ids.isEmpty()) {
            BotTypeManager.setAndStartBots(ids, BotTypeManager.BotType.KPQ_BOT);
        }
        player.yellowMessage("Spawned " + spawned + "/" + n + " KPQ bots.");
    }

    private static int killAll() {
        int n = 0;
        for (KPQBot bot : snapshot()) {
            try {
                KPQOrchestrator.getInstance().unregisterBot(bot);
                BotTypeManager.manuallyStopBot(bot.getChr());
                n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }

    private static List<KPQBot> snapshot() {
        List<KPQBot> out = new ArrayList<>();
        for (Map.Entry<Integer, BotSM> e : CharacterStorage.getAllBots().entrySet()) {
            if (e.getValue() instanceof KPQBot k) {
                out.add(k);
            }
        }
        return out;
    }
}
