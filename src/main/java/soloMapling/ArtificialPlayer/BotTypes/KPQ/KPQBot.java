package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;
import net.server.world.Party;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQSharedContext.KPQPhase;

import java.awt.Point;
import java.util.Collections;
import java.util.List;

import static soloMapling.BotLogger.log;
import static soloMapling.DebugUtilities.debugprint;

/**
 * Kerning PQ companion bot.
 *
 * Lobby: spam recruit chat, auto-accept party invites.
 * Inside: follow party leader across instance maps, attack nearby mobs,
 * loot coupons/passes, stand near leader on rope stages.
 *
 * Stage clear / Cloto quizzes are still driven by the human leader — bots
 * supply party size, DPS, and platform presence.
 */
public class KPQBot extends BotSM {

    public enum KPQBotState {
        RESET,
        RECRUITMENT,
        IN_PARTY_IDLE,
        INSIDE_FOLLOW,
        COMBAT,
        EXIT
    }

    private final KPQOrchestrator orchestrator;
    private final KPQSharedContext sharedContext;
    private volatile KPQBotState state = KPQBotState.RESET;
    private long lastRecruitMessageAt;
    private long lastFollowAt;
    private List<String> hint = Collections.singletonList(getChr().getName());

    public KPQBot(Character character) {
        super(character);
        this.botType = "KPQBot";
        this.dialoguePath = null;
        this.orchestrator = KPQOrchestrator.getInstance();
        this.sharedContext = orchestrator.getSharedContext();
        orchestrator.registerBot(this);
    }

    public KPQBotState getKPQBotState() {
        return state;
    }

    public void setStateForDebug(KPQBotState s) {
        this.state = s;
    }

    private void transitionTo(KPQBotState next, String reason) {
        if (state == next) {
            return;
        }
        KPQBotState prev = state;
        state = next;
        log(String.format("[KPQBot %s] %s -> %s | %s | map=%d",
                getChr().getName(), prev, next, reason, getChr().getMapId()));
        debugprint("[KPQBot]", getChr().getName(), prev, "->", next, "|", reason);
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        // KPQ bots don't need chat menus for MVP
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }

        // Party accept + leader tracking every tick
        if (BotPartyLogic.checkPartyQueue(getChr())) {
            orchestrator.noteLeaderFromBot(this);
            transitionTo(KPQBotState.IN_PARTY_IDLE, "accepted party invite");
        }

        if (isInParty()) {
            orchestrator.noteLeaderFromBot(this);
        }

        int mapId = getChr().getMapId();
        if (KPQConstants.isKpqMap(mapId)) {
            if (state != KPQBotState.INSIDE_FOLLOW && state != KPQBotState.COMBAT) {
                transitionTo(KPQBotState.INSIDE_FOLLOW, "on KPQ map " + mapId);
            }
        } else if (mapId == KPQConstants.KPQ_EXIT) {
            transitionTo(KPQBotState.EXIT, "exit map");
        } else if (mapId == KPQConstants.KPQ_LOBBY) {
            if (isInParty()) {
                if (state != KPQBotState.IN_PARTY_IDLE) {
                    transitionTo(KPQBotState.IN_PARTY_IDLE, "lobby with party");
                }
            } else if (state != KPQBotState.RECRUITMENT && state != KPQBotState.RESET) {
                transitionTo(KPQBotState.RECRUITMENT, "lobby without party");
            }
        }

        switch (state) {
            case RESET -> {
                lastRecruitMessageAt = 0;
                lastFollowAt = 0;
                transitionTo(KPQBotState.RECRUITMENT, "boot");
            }
            case RECRUITMENT -> handleRecruitment();
            case IN_PARTY_IDLE -> handleInPartyIdle();
            case INSIDE_FOLLOW, COMBAT -> handleInside();
            case EXIT -> handleExit();
        }
    }

    private void handleRecruitment() {
        if (isInParty()) {
            transitionTo(KPQBotState.IN_PARTY_IDLE, "joined party while recruiting");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRecruitMessageAt >= KPQConstants.RECRUIT_MESSAGE_INTERVAL_MS) {
            SocialCommands.BotSpeak(getChr(), KPQRecruitMessages.generate(getChr()));
            lastRecruitMessageAt = now;
        }
    }

    private void handleInPartyIdle() {
        if (!isInParty()) {
            transitionTo(KPQBotState.RECRUITMENT, "left party");
            return;
        }
        // Leader will start Lakelis; orchestrator warps us when they enter stage 1.
        Character leader = getPartyLeader();
        if (leader != null && KPQConstants.isKpqMap(leader.getMapId())) {
            orchestrator.followLeaderNow(getChr());
            transitionTo(KPQBotState.INSIDE_FOLLOW, "leader entered KPQ");
        }
    }

    private void handleInside() {
        if (!isInParty()) {
            transitionTo(KPQBotState.RECRUITMENT, "party lost inside");
            return;
        }

        Character leader = getPartyLeader();
        if (leader == null) {
            return;
        }

        // Stay on leader's instance map
        long now = System.currentTimeMillis();
        if (now - lastFollowAt >= KPQConstants.FOLLOW_LEADER_INTERVAL_MS) {
            lastFollowAt = now;
            if (getChr().getMapId() != leader.getMapId()
                    || getChr().getMap() != leader.getMap()) {
                orchestrator.followLeaderNow(getChr());
            } else {
                // stick near leader for rope stages / coupon hand-in
                Point lp = leader.getPosition();
                Point bp = getChr().getPosition();
                if (lp != null && bp != null && lp.distanceSq(bp) > 40_000) { // >200px
                    try {
                        soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation(
                                getChr(),
                                new Point(lp.x + (getChr().getId() % 5) * 28 - 56, lp.y),
                                leader.getMap());
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Combat assist: hit nearest monster, loot coupons/passes
        tryCombatAndLoot();
    }

    private void tryCombatAndLoot() {
        Character chr = getChr();
        if (chr.getMap() == null) {
            return;
        }

        // Loot coupons / passes near us
        try {
            DropCommands.botLoot(chr, 4000);
        } catch (Exception ignored) {
        }

        Monster target = null;
        double best = Double.MAX_VALUE;
        for (MapObject obj : chr.getMap().getMapObjects()) {
            if (obj.getType() != MapObjectType.MONSTER) {
                continue;
            }
            Monster m = (Monster) obj;
            if (m.getHp() <= 0) {
                continue;
            }
            double d = chr.getPosition().distanceSq(m.getPosition());
            if (d < best && d < 250_000) { // ~500px
                best = d;
                target = m;
            }
        }
        if (target != null) {
            transitionTo(KPQBotState.COMBAT, "fighting oid=" + target.getObjectId());
            try {
                BotAttack.basicSwing(chr);
                // light contact damage via existing attack pack if available
                BotAttack.basicSwing(chr);
            } catch (Exception ignored) {
            }
        } else if (state == KPQBotState.COMBAT) {
            transitionTo(KPQBotState.INSIDE_FOLLOW, "no nearby mobs");
        }
    }

    private void handleExit() {
        // Wait for leader to leave exit map, then go back to Kerning lobby recruit
        if (getChr().getMapId() == KPQConstants.KPQ_LOBBY || !KPQConstants.isKpqMap(getChr().getMapId())) {
            if (getChr().getMapId() != KPQConstants.KPQ_LOBBY) {
                try {
                    MapleMapWarp.lobby(getChr());
                } catch (Exception ignored) {
                }
            }
            if (!isInParty()) {
                transitionTo(KPQBotState.RECRUITMENT, "back in city");
            } else {
                transitionTo(KPQBotState.IN_PARTY_IDLE, "party still up after exit");
            }
        }
    }

    private boolean isInParty() {
        return getChr().getParty() != null;
    }

    private Character getPartyLeader() {
        Party party = getChr().getParty();
        if (party == null) {
            return null;
        }
        try {
            return getChr().getClient().getChannelServer()
                    .getPlayerStorage().getCharacterById(party.getLeaderId());
        } catch (Exception e) {
            return null;
        }
    }

    /** Small helper to avoid importing map factory in multiple places. */
    private static final class MapleMapWarp {
        static void lobby(Character chr) {
            var map = chr.getClient().getChannelServer().getMapFactory().getMap(KPQConstants.KPQ_LOBBY);
            soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation(
                    chr, KPQConstants.LOBBY_ANCHOR, map);
        }
    }
}
