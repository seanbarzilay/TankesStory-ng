package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DropIndexTest {

    @Test
    void byMob_returnsAllDropsForMob() {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 1002357, 1, 1, 100000, "drop_data"));
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        List<DropIndex.Entry> drops = idx.byMob(100100);
        assertEquals(2, drops.size());
    }

    @Test
    void byItem_returnsAllSourcesForItem() {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        List<DropIndex.Entry> sources = idx.byItem(4000019);
        assertEquals(2, sources.size());
    }

    @Test
    void byMob_unknown_returnsEmpty() {
        DropIndex idx = new DropIndex();
        assertEquals(0, idx.byMob(999999).size());
    }
}
