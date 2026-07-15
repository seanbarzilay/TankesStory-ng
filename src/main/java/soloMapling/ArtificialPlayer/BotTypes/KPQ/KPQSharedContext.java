package soloMapling.ArtificialPlayer.BotTypes.KPQ;

/**
 * Blackboard for one Kerning PQ run (orchestrator writes, bots read).
 */
public class KPQSharedContext {

    public enum KPQPhase {
        INACTIVE,
        RECRUITMENT,
        IN_PARTY_IDLE,
        INSIDE_PQ,
        EXIT
    }

    private volatile KPQPhase currentPhase = KPQPhase.INACTIVE;
    private volatile boolean pqActive = false;
    private volatile int leaderMapId = 0;

    public KPQPhase getCurrentPhase() { return currentPhase; }
    public boolean isPqActive() { return pqActive; }
    public int getLeaderMapId() { return leaderMapId; }

    void setCurrentPhase(KPQPhase phase) { this.currentPhase = phase; }
    void setPqActive(boolean v) { this.pqActive = v; }
    void setLeaderMapId(int mapId) { this.leaderMapId = mapId; }

    void reset() {
        currentPhase = KPQPhase.INACTIVE;
        pqActive = false;
        leaderMapId = 0;
    }
}
