package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobMapIndexTest {

    @Test
    void mapsFor_returnsEachMapWhereTheMobAppears() {
        MobMapIndex idx = build(Map.of(
                100000000, List.of(100, 200),
                100000001, List.of(200, 300)
        ));
        assertEquals(List.of(100000000), idx.mapsFor(100));
        assertEquals(List.of(100000000, 100000001), idx.mapsFor(200));
        assertEquals(List.of(100000001), idx.mapsFor(300));
    }

    @Test
    void mapsFor_unknownMob_returnsEmpty() {
        MobMapIndex idx = build(Map.of(100000000, List.of(100)));
        assertTrue(idx.mapsFor(999).isEmpty());
    }

    @Test
    void mapsFor_dedupesRepeatsOnSameMap() {
        MobMapIndex idx = build(Map.of(100000000, List.of(100, 100, 100)));
        assertEquals(List.of(100000000), idx.mapsFor(100));
    }

    @Test
    void mapsFor_returnsResultsSortedByMapId() {
        MobMapIndex idx = build(Map.of(
                300000000, List.of(42),
                100000000, List.of(42),
                200000000, List.of(42)
        ));
        assertEquals(List.of(100000000, 200000000, 300000000), idx.mapsFor(42));
    }

    @Test
    void loadFrom_skipsNullMobIdLists() {
        NameIndex names = new NameIndex();
        names.add(NameIndex.Kind.MAP, 100000000, "Henesys");
        names.add(NameIndex.Kind.MAP, 100000001, "Empty");
        names.add(NameIndex.Kind.ITEM, 999, "ShouldBeIgnored");

        MobMapIndex idx = MobMapIndex.loadFrom(names, mapId ->
                mapId == 100000000 ? List.of(100, 200) : null);

        assertEquals(List.of(100000000), idx.mapsFor(100));
        assertEquals(List.of(100000000), idx.mapsFor(200));
        assertEquals(2, idx.size());
    }

    private MobMapIndex build(Map<Integer, List<Integer>> mapToMobs) {
        NameIndex names = new NameIndex();
        for (int mapId : mapToMobs.keySet()) {
            names.add(NameIndex.Kind.MAP, mapId, "Map " + mapId);
        }
        return MobMapIndex.loadFrom(names, mapToMobs::get);
    }
}
