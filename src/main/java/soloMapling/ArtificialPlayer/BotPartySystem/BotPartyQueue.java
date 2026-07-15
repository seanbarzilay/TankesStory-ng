package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;

import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotPartyQueue {

    public static final class PartyInviteEntry {
        private final Character inviter;
        private final int partyId;

        public PartyInviteEntry(Character inviter, int partyId) {
            this.inviter = inviter;
            this.partyId = partyId;
        }

        public Character getInviter() {
            return inviter;
        }

        public int getPartyId() {
            return partyId;
        }
    }

    // Key by character id — Character identity is unsafe as a map key across systems.
    private final ConcurrentHashMap<Integer, PartyInviteEntry> queues;
    private static final BotPartyQueue instance = new BotPartyQueue();

    private BotPartyQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotPartyQueue getInstance() {
        return instance;
    }

    // Last-wins: the entry always mirrors the LATEST invite the engine actually created.
    public void addPartyInvite(Character fakechar, Character inviter, int partyId) {
        debugprint("addPartyInvite: bot=" + fakechar.getName() + ", inviter=" + inviter.getName() + ", partyId=" + partyId);
        queues.put(fakechar.getId(), new PartyInviteEntry(inviter, partyId));

        // PQ companion bots (OPQ / KPQ) auto-accept immediately — no dialogue arm window.
        // Without this, a slow tick or a shared recruit poll path can leave invites hanging
        // or race with decline-style handlers on other bot types.
        BotSM sm = CharacterStorage.getAllBots().get(fakechar.getId());
        if (sm instanceof OPQBot || sm instanceof KPQBot
                || (sm != null && ("OPQBot".equals(sm.getBotType()) || "KPQBot".equals(sm.getBotType())))) {
            boolean ok = BotPartyCommands.botAcceptPartyInvite(fakechar);
            debugprint("addPartyInvite: auto-accept PQ bot " + fakechar.getName() + " joined=" + ok);
            if (ok && sm instanceof OPQBot opq) {
                try {
                    soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQOrchestrator.getInstance()
                            .noteLeaderFromBot(opq);
                } catch (Exception ignored) {
                }
            }
            if (ok && sm instanceof KPQBot kpq) {
                try {
                    soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQOrchestrator.getInstance()
                            .noteLeaderFromBot(kpq);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        // Dialogue-driven bots (Training / Social / Follower): wake their tick so pollInvites runs.
        BotRecruitManager.wakeBotForInvite(fakechar);
    }

    public PartyInviteEntry getPartyInvite(Character fakechar) {
        return queues.get(fakechar.getId());
    }

    public boolean hasPendingInvite(Character fakechar) {
        return queues.containsKey(fakechar.getId());
    }

    public void removePartyInvite(Character fakechar) {
        queues.remove(fakechar.getId());
    }
}
