package soloMapling.ArtificialPlayer.BotTypes.KPQ;

import client.Character;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Lobby recruit spam for Kerning PQ bots. */
public final class KPQRecruitMessages {

    private KPQRecruitMessages() {}

    private static final List<String> PREFIXES = List.of(
            "J>", "LFP>", "Join>", "lf>", "KPQ>"
    );
    private static final List<String> NAMES = List.of(
            "KPQ", "Kerning PQ", "1st Acc", "kerning pq", "KPQ plz"
    );
    private static final List<String> FILLERS = List.of(
            "!!!", "plz", "asap", "need 1", "can tank"
    );

    public static String generate(Character chr) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        if (chr != null && r.nextDouble() < 0.35) {
            sb.append("Lvl ").append(chr.getLevel()).append(' ');
            try {
                sb.append(chr.getJob().name()).append(' ');
            } catch (Exception ignored) {
            }
        }
        sb.append(PREFIXES.get(r.nextInt(PREFIXES.size()))).append(' ');
        sb.append(NAMES.get(r.nextInt(NAMES.size())));
        int n = r.nextInt(3);
        for (int i = 0; i < n; i++) {
            sb.append(' ').append(FILLERS.get(r.nextInt(FILLERS.size())));
        }
        String out = sb.toString();
        if (r.nextDouble() < 0.15) {
            out = out.toUpperCase();
        }
        return out;
    }
}
