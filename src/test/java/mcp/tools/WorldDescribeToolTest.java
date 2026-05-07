package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldDescribeToolTest {

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.world.describe",
                new WorldDescribeTool(() -> 0L, List::of).name());
    }

    @Test
    void call_returnsUptimeAndWorlds() throws Exception {
        WorldDescribeTool.WorldStats w0 = new WorldDescribeTool.WorldStats(0, "Scania", 3, 42, 10, 10, 10);
        WorldDescribeTool.WorldStats w1 = new WorldDescribeTool.WorldStats(1, "Bera", 3, 0, 100, 100, 10);
        WorldDescribeTool tool = new WorldDescribeTool(() -> 1234L, () -> List.of(w0, w1));
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(1234, out.get("uptime_seconds").asLong());
        assertEquals(2, out.get("worlds").size());
        assertEquals("Scania", out.get("worlds").get(0).get("name").asText());
        assertEquals(42, out.get("worlds").get(0).get("online_count").asInt());
    }
}
