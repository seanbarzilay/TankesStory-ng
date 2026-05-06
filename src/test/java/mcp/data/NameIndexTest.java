package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameIndexTest {

    @Test
    void search_prefixMatchesRankFirst() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Maple Sword");
        idx.add(NameIndex.Kind.ITEM, 2, "Sword of Maple");
        idx.add(NameIndex.Kind.ITEM, 3, "Generic Sword");
        List<NameIndex.Hit> hits = idx.search("maple", null, 10);
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).id());
    }

    @Test
    void search_filterByKind() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Foo Bar");
        idx.add(NameIndex.Kind.MOB, 2, "Foo Bar");
        List<NameIndex.Hit> hits = idx.search("foo", NameIndex.Kind.MOB, 10);
        assertEquals(1, hits.size());
        assertEquals(NameIndex.Kind.MOB, hits.get(0).kind());
    }

    @Test
    void search_respectsLimit() {
        NameIndex idx = new NameIndex();
        for (int i = 0; i < 50; i++) idx.add(NameIndex.Kind.ITEM, i, "item " + i);
        List<NameIndex.Hit> hits = idx.search("item", null, 5);
        assertEquals(5, hits.size());
    }

    @Test
    void search_isCaseInsensitive() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Maple Sword");
        assertTrue(idx.search("MAPLE", null, 10).size() == 1);
    }
}
