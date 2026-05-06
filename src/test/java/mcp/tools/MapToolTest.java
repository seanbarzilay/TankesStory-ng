package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapToolTest {

    @Test
    void call_missingId_throwsInvalidParams() {
        MapTool tool = new MapTool();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> tool.call(JsonRpc.MAPPER.createObjectNode()));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.map.describe", new MapTool().name());
    }

    @Test
    void inputSchema_requiresId() {
        JsonNode schema = new MapTool().inputSchema();
        assertEquals("object", schema.get("type").asText());
    }
}
