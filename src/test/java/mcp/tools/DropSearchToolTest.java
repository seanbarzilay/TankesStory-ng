package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.DropIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DropSearchToolTest {

    @Test
    void call_byMob_returnsDrops() throws Exception {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        DropSearchTool tool = new DropSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 100100);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("drops").size());
        assertEquals(4000019, out.get("drops").get(0).get("itemId").asInt());
    }

    @Test
    void call_byItem_returnsSources() throws Exception {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        DropSearchTool tool = new DropSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("item_id", 4000019);
        JsonNode out = tool.call(args);
        assertEquals(2, out.get("drops").size());
    }

    @Test
    void call_neitherIdProvided_throws() {
        DropSearchTool tool = new DropSearchTool(new DropIndex());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_bothIdsProvided_throws() {
        DropSearchTool tool = new DropSearchTool(new DropIndex());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 1);
        args.put("item_id", 2);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
