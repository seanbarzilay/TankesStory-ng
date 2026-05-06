package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NameSearchToolTest {

    private NameIndex index() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1002357, "Beginner's Glasses");
        idx.add(NameIndex.Kind.MOB, 100100, "Snail");
        return idx;
    }

    @Test
    void call_returnsMatches() throws Exception {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "begin");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("hits").size());
        assertEquals(1002357, out.get("hits").get(0).get("id").asInt());
    }

    @Test
    void call_filtersByKind() throws Exception {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", " ");
        args.put("kind", "mob");
        JsonNode out = tool.call(args);
        for (JsonNode hit : out.get("hits")) assertEquals("MOB", hit.get("kind").asText());
    }

    @Test
    void call_invalidKind_throws() {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "x");
        args.put("kind", "bogus");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_capLimit() throws Exception {
        NameIndex idx = new NameIndex();
        for (int i = 0; i < 200; i++) idx.add(NameIndex.Kind.ITEM, i, "item " + i);
        NameSearchTool tool = new NameSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "item");
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("hits").size());
    }
}
