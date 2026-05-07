package mcp.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

public class MobMapIndex {

    private final Map<Integer, List<Integer>> mobToMaps;

    private MobMapIndex(Map<Integer, List<Integer>> mobToMaps) {
        this.mobToMaps = mobToMaps;
    }

    public static MobMapIndex loadFrom(NameIndex names, IntFunction<List<Integer>> mobIdsForMap) {
        Map<Integer, LinkedHashSet<Integer>> raw = new HashMap<>();
        for (NameIndex.Hit map : names.search("", NameIndex.Kind.MAP, Integer.MAX_VALUE)) {
            List<Integer> mobIds = mobIdsForMap.apply(map.id());
            if (mobIds == null) continue;
            for (int mobId : mobIds) {
                raw.computeIfAbsent(mobId, k -> new LinkedHashSet<>()).add(map.id());
            }
        }
        Map<Integer, List<Integer>> built = new HashMap<>(raw.size());
        for (var e : raw.entrySet()) {
            List<Integer> sorted = new ArrayList<>(e.getValue());
            Collections.sort(sorted);
            built.put(e.getKey(), List.copyOf(sorted));
        }
        return new MobMapIndex(Map.copyOf(built));
    }

    public List<Integer> mapsFor(int mobId) {
        return mobToMaps.getOrDefault(mobId, List.of());
    }

    public int size() { return mobToMaps.size(); }
}
