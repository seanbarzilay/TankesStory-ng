package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import java.awt.Point;
import java.util.List;

/**
 * Maps / items / lobby constants for Kerning Party Quest (1st Accompaniment).
 * Matches Cosmic scripts: event/KerningPQ.js, npc/9020000-9020002, portal/kpq0-4.
 */
public final class KPQConstants {

    private KPQConstants() {}

    // ---- Maps ---------------------------------------------------------------
    public static final int KPQ_LOBBY   = 103000000; // Kerning City (Lakelis)
    public static final int KPQ_STAGE_1 = 103000800;
    public static final int KPQ_STAGE_2 = 103000801;
    public static final int KPQ_STAGE_3 = 103000802;
    public static final int KPQ_STAGE_4 = 103000803;
    public static final int KPQ_STAGE_5 = 103000804; // King Slime
    public static final int KPQ_BONUS   = 103000805;
    public static final int KPQ_EXIT    = 103000890;

    public static final List<Integer> KPQ_INSIDE_MAPS = List.of(
            KPQ_STAGE_1, KPQ_STAGE_2, KPQ_STAGE_3, KPQ_STAGE_4, KPQ_STAGE_5, KPQ_BONUS
    );

    // ---- NPCs ---------------------------------------------------------------
    public static final int LAKELIS = 9020000; // entry in Kerning
    public static final int CLOTO   = 9020001; // stage NPC
    public static final int NELLA   = 9020002; // exit helper

    // ---- Items --------------------------------------------------------------
    public static final int COUPON = 4001007; // stage 1 coupons
    public static final int PASS   = 4001008; // stage passes

    // ---- Level / party (must match KerningPQ.js) ----------------------------
    public static final int MIN_LEVEL = 21;
    public static final int MAX_LEVEL = 100;
    public static final int MIN_PARTY = 2;
    public static final int MAX_PARTY = 4;

    // ---- Timing -------------------------------------------------------------
    public static final long RECRUIT_MESSAGE_INTERVAL_MS = 12_000;
    public static final long FOLLOW_LEADER_INTERVAL_MS   = 2_000;

    // Default lobby stand position (Kerning town center-ish; refined on spawn platforms)
    public static final Point LOBBY_ANCHOR = new Point(-216, 275);

    public static boolean isKpqMap(int mapId) {
        return mapId >= KPQ_STAGE_1 && mapId <= KPQ_BONUS;
    }

    public static int stageIndex(int mapId) {
        return switch (mapId) {
            case KPQ_STAGE_1 -> 1;
            case KPQ_STAGE_2 -> 2;
            case KPQ_STAGE_3 -> 3;
            case KPQ_STAGE_4 -> 4;
            case KPQ_STAGE_5 -> 5;
            case KPQ_BONUS   -> 6;
            default -> 0;
        };
    }
}
