package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDescribeToolTest {

    @Test
    void name_isCorrect() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        assertEquals("cosmic.admin.player.describe", new PlayerDescribeTool(pl).name());
    }

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online (12)

    @Test
    void call_onlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 1500, 0, 1, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Foo");
        JsonNode out = tool.call(args);
        assertEquals("Foo", out.get("name").asText());
        assertEquals(50, out.get("level").asInt());
        assertEquals(100, out.get("job").asInt());
        assertTrue(out.get("online").asBoolean());
    }

    @Test
    void call_offlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot bar = new PlayerLookup.Snapshot("Bar", 30, 0, 0, 0, 0, 0, 800, 50, 1000, 0, false);
        PlayerLookup pl = new PlayerLookup(List::of, name -> "Bar".equalsIgnoreCase(name) ? Optional.of(bar) : Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Bar");
        JsonNode out = tool.call(args);
        assertEquals(false, out.get("online").asBoolean());
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "missing");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("no such player"));
    }
}
