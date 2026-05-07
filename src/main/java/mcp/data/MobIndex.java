package mcp.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

public class MobIndex {

    public record MobMeta(int level, boolean boss) {}

    public record Entry(int id, String name, int level, boolean boss) {}

    private final List<Entry> entries;

    private MobIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public static MobIndex loadFrom(NameIndex names, IntFunction<MobMeta> statsLookup) {
        List<Entry> built = new ArrayList<>();
        for (NameIndex.Hit h : names.search("", NameIndex.Kind.MOB, Integer.MAX_VALUE)) {
            MobMeta meta = statsLookup.apply(h.id());
            if (meta == null) continue;
            built.add(new Entry(h.id(), h.name(), meta.level(), meta.boss()));
        }
        return new MobIndex(List.copyOf(built));
    }

    public int size() { return entries.size(); }

    public List<Entry> search(int minLevel, int maxLevel, Boolean bossFilter, int limit) {
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            if (e.level < minLevel || e.level > maxLevel) continue;
            if (bossFilter != null && e.boss != bossFilter) continue;
            matched.add(e);
        }
        matched.sort(Comparator
                .<Entry>comparingInt(Entry::level)
                .thenComparingInt(Entry::id));
        if (matched.size() > limit) return matched.subList(0, limit);
        return matched;
    }
}
