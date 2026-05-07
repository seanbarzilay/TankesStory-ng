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

    @Test
    void call_onlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 1500, 0, 1, 100000000, 1000, 100, 5000, 0, true, null, 42);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Foo");
        JsonNode out = tool.call(args);
        assertEquals("Foo", out.get("name").asText());
        assertEquals(50, out.get("level").asInt());
        assertEquals(100, out.get("job").asInt());
        assertTrue(out.get("online").asBoolean());
        assertEquals(42, out.get("inventory_item_count").asInt());
        assertTrue(out.get("last_login_epoch_ms").isNull() || out.get("last_login_epoch_ms").isNumber());
    }

    @Test
    void call_offlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot bar = new PlayerLookup.Snapshot("Bar", 30, 0, 0, 0, 0, 0, 800, 50, 1000, 0, false, 1700000000000L, 0);
        PlayerLookup pl = new PlayerLookup(List::of, name -> "Bar".equalsIgnoreCase(name) ? Optional.of(bar) : Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Bar");
        JsonNode out = tool.call(args);
        assertEquals(false, out.get("online").asBoolean());
        assertEquals(1700000000000L, out.get("last_login_epoch_ms").asLong());
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
