package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;
import static soloMapling.BotLogger.log;

/**
 * Coordinates Kerning PQ bots: track party leader, drag bots into the leader's
 * instance maps when the PQ starts / advances stages.
 */
public class KPQOrchestrator {

    private static final long TICK_PERIOD_MS = 1500;
    private static final KPQOrchestrator INSTANCE = new KPQOrchestrator();

    public static KPQOrchestrator getInstance() {
        return INSTANCE;
    }

    private final KPQSharedContext context = new KPQSharedContext();
    private final List<KPQBot> registeredBots = new CopyOnWriteArrayList<>();
    private volatile int leaderId = -1;
    private ScheduledFuture<?> tickHandle;

    private KPQOrchestrator() {
        ensureTickRunning();
    }

    public KPQSharedContext getSharedContext() {
        return context;
    }

    public synchronized void registerBot(KPQBot bot) {
        if (!registeredBots.contains(bot)) {
            registeredBots.add(bot);
            kpqLog("Registered bot " + bot.getChr().getName() + " (n=" + registeredBots.size() + ")");
        }
        ensureTickRunning();
    }

    public synchronized void unregisterBot(KPQBot bot) {
        registeredBots.remove(bot);
    }

    public void noteLeaderFromBot(KPQBot bot) {
        Character chr = bot.getChr();
        Party party = chr.getParty();
        if (party == null) {
            return;
        }
        int lid = party.getLeaderId();
        if (lid > 0 && lid != leaderId) {
            leaderId = lid;
            kpqLog("Leader noted: " + lid + " via " + chr.getName());
        }
        if (!context.isPqActive()) {
            context.setPqActive(true);
            context.setCurrentPhase(KPQSharedContext.KPQPhase.RECRUITMENT);
        }
    }

    public synchronized void resetForNewRun() {
        kpqLog("Reset for new run");
        context.reset();
        context.setPqActive(true);
        context.setCurrentPhase(KPQSharedContext.KPQPhase.RECRUITMENT);
        leaderId = -1;
    }

    public synchronized void shutdownRun() {
        kpqLog("Shutdown run");
        context.reset();
        leaderId = -1;
    }

    public List<KPQBot> getRegisteredBots() {
        return List.copyOf(registeredBots);
    }

    public int getLeaderId() {
        return leaderId;
    }

    private void ensureTickRunning() {
        if (tickHandle != null && !tickHandle.isCancelled() && !tickHandle.isDone()) {
            return;
        }
        tickHandle = ExecutorServiceManager.getScheduledExecutorService()
                .scheduleWithFixedDelay(this::safeTick, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tick() {
        if (registeredBots.isEmpty()) {
            return;
        }

        Character leader = resolveLeader();
        if (leader == null) {
            // try pull leader from any bot still in a party
            for (KPQBot bot : registeredBots) {
                if (bot.getChr().getParty() != null) {
                    noteLeaderFromBot(bot);
                    leader = resolveLeader();
                    break;
                }
            }
        }

        if (leader == null) {
            return;
        }

        int leaderMap = leader.getMapId();
        context.setLeaderMapId(leaderMap);

        if (KPQConstants.isKpqMap(leaderMap)) {
            context.setCurrentPhase(KPQSharedContext.KPQPhase.INSIDE_PQ);
            context.setPqActive(true);
            pullBotsToLeader(leader);
        } else if (leaderMap == KPQConstants.KPQ_LOBBY) {
            if (context.getCurrentPhase() == KPQSharedContext.KPQPhase.INSIDE_PQ) {
                context.setCurrentPhase(KPQSharedContext.KPQPhase.EXIT);
            } else if (anyBotInParty()) {
                context.setCurrentPhase(KPQSharedContext.KPQPhase.IN_PARTY_IDLE);
            } else {
                context.setCurrentPhase(KPQSharedContext.KPQPhase.RECRUITMENT);
            }
        } else if (leaderMap == KPQConstants.KPQ_EXIT) {
            context.setCurrentPhase(KPQSharedContext.KPQPhase.EXIT);
        }
    }

    private boolean anyBotInParty() {
        for (KPQBot bot : registeredBots) {
            if (bot.getChr().getParty() != null) {
                return true;
            }
        }
        return false;
    }

    private Character resolveLeader() {
        if (leaderId <= 0 || registeredBots.isEmpty()) {
            return null;
        }
        KPQBot anchor = registeredBots.get(0);
        return anchor.getChr().getClient().getChannelServer()
                .getPlayerStorage().getCharacterById(leaderId);
    }

    /**
     * Warp every registered bot that shares the leader's party onto the leader's
     * current map (instance map when inside PQ). Also attaches bots to the leader's
     * EventInstance when present.
     */
    private void pullBotsToLeader(Character leader) {
        MapleMap targetMap = leader.getMap();
        if (targetMap == null) {
            return;
        }
        EventInstanceManager eim = leader.getEventInstance();
        Party party = leader.getParty();
        if (party == null) {
            return;
        }

        for (KPQBot bot : registeredBots) {
            Character chr = bot.getChr();
            Party p = chr.getParty();
            if (p == null || p.getId() != party.getId()) {
                continue;
            }
            // Attach to event instance so stage scripts / portals see the bot
            if (eim != null && chr.getEventInstance() != eim) {
                try {
                    eim.registerPlayer(chr, false); // don't re-run playerEntry (leader already entered)
                    kpqLog("Registered " + chr.getName() + " into EIM " + eim.getName());
                } catch (Exception ex) {
                    kpqLog("EIM register failed for " + chr.getName() + ": " + ex.getMessage());
                }
            }

            if (chr.getMapId() != leader.getMapId() || chr.getMap() != targetMap) {
                try {
                    Point dest = leader.getPosition() != null
                            ? new Point(leader.getPosition())
                            : new Point(0, 0);
                    // small offset so bots don't stack
                    dest.x += (chr.getId() % 7) * 25 - 75;
                    warpBotToLocation(chr, dest, targetMap);
                    kpqLog("Warped " + chr.getName() + " -> map " + targetMap.getId());
                } catch (Exception ex) {
                    kpqLog("Warp failed for " + chr.getName() + ": " + ex.getMessage());
                }
            }
        }
    }

    /** Public helper for bots that want an immediate follow. */
    public void followLeaderNow(Character fakechar) {
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            return;
        }
        if (!KPQConstants.isKpqMap(leader.getMapId()) && leader.getMapId() != KPQConstants.KPQ_EXIT) {
            return;
        }
        Party party = fakechar.getParty();
        if (party == null || party.getLeaderId() != leaderId) {
            return;
        }
        EventInstanceManager eim = leader.getEventInstance();
        if (eim != null && fakechar.getEventInstance() != eim) {
            eim.registerPlayer(fakechar, false);
        }
        Point dest = new Point(leader.getPosition());
        dest.x += (fakechar.getId() % 5) * 30 - 60;
        warpBotToLocation(fakechar, dest, leader.getMap());
    }

    private void kpqLog(String msg) {
        log("[KPQOrchestrator] " + msg);
    }
}
