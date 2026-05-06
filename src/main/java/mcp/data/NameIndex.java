package mcp.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NameIndex {

    public enum Kind { ITEM, MOB, MAP, NPC, SKILL }

    public record Hit(Kind kind, int id, String name) {}

    private record Entry(Kind kind, int id, String name, String lower) {}

    private final List<Entry> entries = new ArrayList<>();

    public void add(Kind kind, int id, String name) {
        if (name == null) return;
        entries.add(new Entry(kind, id, name, name.toLowerCase()));
    }

    public List<Hit> search(String query, Kind filter, int limit) {
        String q = query.toLowerCase();
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            if (filter != null && e.kind != filter) continue;
            if (e.lower.contains(q)) matched.add(e);
        }
        matched.sort(Comparator
                .<Entry>comparingInt(e -> e.lower.startsWith(q) ? 0 : 1)
                .thenComparing(e -> e.lower));
        List<Hit> out = new ArrayList<>(Math.min(limit, matched.size()));
        for (int i = 0; i < matched.size() && i < limit; i++) {
            Entry e = matched.get(i);
            out.add(new Hit(e.kind, e.id, e.name));
        }
        return out;
    }
}
