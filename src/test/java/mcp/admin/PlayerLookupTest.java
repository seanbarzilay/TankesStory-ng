package mcp.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLookupTest {

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online, lastLoginEpochMs, inventoryItemCount (14)

    @Test
    void online_findByName_returnsPresent() {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 0, 0, 1, 100000000, 1000, 100, 5000, 0, true, null, 0);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        Optional<PlayerLookup.Snapshot> found = pl.find("Foo");
        assertTrue(found.isPresent());
        assertEquals(50, found.get().level());
        assertEquals(100, found.get().job());
    }

    @Test
    void online_findCaseInsensitive() {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 0, 0, 1, 100000000, 1000, 100, 5000, 0, true, null, 0);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        assertTrue(pl.find("FOO").isPresent());
        assertTrue(pl.find("foo").isPresent());
    }

    @Test
    void offline_fallsBackToDb_whenNotOnline() {
        PlayerLookup.Snapshot offline = new PlayerLookup.Snapshot("Bar", 30, 200, 0, 0, 1, 0, 800, 50, 1000, 0, false, 1700000000000L, 0);
        PlayerLookup pl = new PlayerLookup(List::of, name -> "Bar".equalsIgnoreCase(name) ? Optional.of(offline) : Optional.empty());
        Optional<PlayerLookup.Snapshot> found = pl.find("Bar");
        assertTrue(found.isPresent());
        assertEquals(false, found.get().online());
        assertEquals(1700000000000L, found.get().lastLoginEpochMs());
    }

    @Test
    void offline_returnsEmpty_whenNotInDb() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        assertTrue(pl.find("missing").isEmpty());
    }

    @Test
    void online_filtered() {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 1, 0, 0, 0, 0, 100, 1, 1, 1, 0, true, null, 0);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 1, 0, 0, 0, 1, 100, 1, 1, 1, 0, true, null, 0);
        PlayerLookup.Snapshot c = new PlayerLookup.Snapshot("C", 1, 0, 0, 1, 0, 100, 1, 1, 1, 0, true, null, 0);
        PlayerLookup pl = new PlayerLookup(() -> List.of(a, b, c), name -> Optional.empty());
        List<PlayerLookup.Snapshot> hits = pl.online(0, null, null, null, 100);
        assertEquals(2, hits.size());
        assertEquals(1, pl.online(0, 1, null, null, 100).size());
    }
}
