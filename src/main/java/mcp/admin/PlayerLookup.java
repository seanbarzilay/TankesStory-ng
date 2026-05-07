package mcp.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class PlayerLookup {

    public record Snapshot(
            String name,
            int level,
            int job,
            long exp,
            int world,
            int channel,
            int map,
            int hp,
            int mp,
            int mesos,
            int gmLevel,
            boolean online,
            Long lastLoginEpochMs,
            int inventoryItemCount
    ) {}

    public interface OnlineProvider extends Supplier<List<Snapshot>> {}
    public interface OfflineLookup extends Function<String, Optional<Snapshot>> {}

    private final OnlineProvider onlineProvider;
    private final OfflineLookup offlineLookup;

    public PlayerLookup(OnlineProvider onlineProvider, OfflineLookup offlineLookup) {
        this.onlineProvider = onlineProvider;
        this.offlineLookup = offlineLookup;
    }

    public Optional<Snapshot> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (Snapshot s : onlineProvider.get()) {
            if (s.name().equalsIgnoreCase(name)) return Optional.of(s);
        }
        return offlineLookup.apply(name);
    }

    public List<Snapshot> online(Integer world, Integer channel, Integer map, String nameSubstring, int limit) {
        List<Snapshot> out = new ArrayList<>();
        String sub = nameSubstring == null ? null : nameSubstring.toLowerCase();
        for (Snapshot s : onlineProvider.get()) {
            if (world != null && s.world() != world) continue;
            if (channel != null && s.channel() != channel) continue;
            if (map != null && s.map() != map) continue;
            if (sub != null && !s.name().toLowerCase().contains(sub)) continue;
            out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
