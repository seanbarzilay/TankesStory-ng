package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.KPQ.KPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;

import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.BotLogger.log;
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

        // Real players: accept immediately. Recruit poll used to reject cold invites unless
        // the bot had been dialogue-armed ("wanna party up?"), which felt broken.
        if (inviter != null && !BotHelpers.isBot(inviter)) {
            boolean ok = BotPartyCommands.botAcceptPartyInvite(fakechar);
            BotSM sm = CharacterStorage.getAllBots().get(fakechar.getId());
            String type = sm == null ? "?" : sm.getBotType();
            log("[BotParty] auto-accept " + fakechar.getName() + " (" + type + ") from "
                    + inviter.getName() + " partyId=" + partyId + " joined=" + ok);
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

        // Bot-to-bot invites: wake recruit tick to drain.
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
