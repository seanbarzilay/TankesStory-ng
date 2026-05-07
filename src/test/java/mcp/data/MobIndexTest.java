package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobIndexTest {

    @Test
    void search_filtersByLevelRange() {
        MobIndex idx = build(Map.of(
                100, new Stub("Slime", 5, false),
                200, new Stub("Snail", 10, false),
                300, new Stub("Boss", 35, true),
                400, new Stub("Drake", 60, false)
        ));

        List<MobIndex.Entry> hits = idx.search(10, 40, null, 50);
        assertEquals(2, hits.size());
        assertEquals(10, hits.get(0).level());     // sorted by level
        assertEquals("Snail", hits.get(0).name());
        assertEquals(35, hits.get(1).level());
    }

    @Test
    void search_bossFilter_true_returnsOnlyBosses() {
        MobIndex idx = build(Map.of(
                100, new Stub("Mob", 30, false),
                200, new Stub("Boss1", 30, true),
                300, new Stub("Boss2", 35, true)
        ));
        List<MobIndex.Entry> hits = idx.search(0, 100, true, 50);
        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(MobIndex.Entry::boss));
    }

    @Test
    void search_bossFilter_false_excludesBosses() {
        MobIndex idx = build(Map.of(
                100, new Stub("Mob", 30, false),
                200, new Stub("Boss", 30, true)
        ));
        List<MobIndex.Entry> hits = idx.search(0, 100, false, 50);
        assertEquals(1, hits.size());
        assertEquals("Mob", hits.get(0).name());
    }

    @Test
    void search_limitTruncates() {
        Map<Integer, Stub> stubs = new HashMap<>();
        for (int i = 0; i < 10; i++) stubs.put(100 + i, new Stub("M" + i, 30, false));
        MobIndex idx = build(stubs);
        assertEquals(3, idx.search(0, 100, null, 3).size());
    }

    @Test
    void loadFrom_skipsMobsWithNoStats() {
        NameIndex names = new NameIndex();
        names.add(NameIndex.Kind.MOB, 100, "Known");
        names.add(NameIndex.Kind.MOB, 200, "Unknown");
        names.add(NameIndex.Kind.ITEM, 300, "ShouldBeIgnored");

        MobIndex idx = MobIndex.loadFrom(names, id -> id == 100
                ? new MobIndex.MobMeta(20, false)
                : null);

        assertEquals(1, idx.size());
        List<MobIndex.Entry> hits = idx.search(0, 100, null, 10);
        assertEquals(100, hits.get(0).id());
    }

    private record Stub(String name, int level, boolean boss) {}

    private MobIndex build(Map<Integer, Stub> stubs) {
        NameIndex names = new NameIndex();
        for (var e : stubs.entrySet()) {
            names.add(NameIndex.Kind.MOB, e.getKey(), e.getValue().name());
        }
        return MobIndex.loadFrom(names, id -> {
            Stub s = stubs.get(id);
            return s == null ? null : new MobIndex.MobMeta(s.level(), s.boss());
        });
    }
}
