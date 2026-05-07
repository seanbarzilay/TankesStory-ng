package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineToolTest {

    private PlayerLookup lookup(PlayerLookup.Snapshot... players) {
        return new PlayerLookup(() -> List.of(players), name -> Optional.empty());
    }

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online (12)

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.online", new OnlineTool(lookup()).name());
    }

    @Test
    void call_emptyArgs_returnsAllOnline() throws Exception {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 50, 100, 0, 0, 0, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 70, 200, 0, 0, 1, 100000001, 1500, 200, 8000, 0, true);
        OnlineTool tool = new OnlineTool(lookup(a, b));
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(2, out.get("players").size());
        assertEquals(2, out.get("total").asInt());
        assertEquals(100, out.get("players").get(0).get("job").asInt());
    }

    @Test
    void call_filterByWorld() throws Exception {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 70, 0, 0, 1, 0, 0, 0, 0, 0, 0, true);
        OnlineTool tool = new OnlineTool(lookup(a, b));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world", 1);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("players").size());
        assertEquals("B", out.get("players").get(0).get("name").asText());
    }

    @Test
    void call_limitCappedAt200() throws Exception {
        PlayerLookup.Snapshot[] arr = new PlayerLookup.Snapshot[300];
        for (int i = 0; i < 300; i++) {
            arr[i] = new PlayerLookup.Snapshot("p" + i, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        }
        OnlineTool tool = new OnlineTool(lookup(arr));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertTrue(out.get("players").size() <= 200);
    }
}
