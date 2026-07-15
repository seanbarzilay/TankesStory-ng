package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotTradeQueue {

    // Written from netty threads (Trade.inviteTrade) and read from bot tick virtual threads.
    private final Map<Character, Character> queues;
    private static final BotTradeQueue botTradeQueue = new BotTradeQueue();

    private BotTradeQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotTradeQueue getInstance() {
        return botTradeQueue;
    }

    public void addTradeRequest(Character fakechar, Character partner) {
        debugprint("addTradeRequest");
        queues.putIfAbsent(fakechar, partner);
    }

    public Character getTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            return queues.get(fakechar);
        }
        return null;
    }

    public boolean hasPendingTrades(Character fakechar) {
        if (queues.containsKey(fakechar)) {
            return true;
        }
        return false;
    }

    public void removeTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            queues.remove(fakechar);
        }
    }


}
