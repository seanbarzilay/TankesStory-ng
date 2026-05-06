package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemToolTest {

    @Test
    void call_known_returnsDescription() throws Exception {
        ItemTool.ItemInfo info = new ItemTool.ItemInfo() {
            @Override public String getName(int id)       { return id == 1002357 ? "Beginner's Glasses" : null; }
            @Override public int    getWholePrice(int id) { return 100; }
            @Override public short  getSlotMax(int id)    { return (short) 1; }
        };

        ItemTool tool = new ItemTool(info);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 1002357);
        JsonNode out = tool.call(args);
        assertEquals(1002357, out.get("id").asInt());
        assertEquals("Beginner's Glasses", out.get("name").asText());
        assertEquals(100, out.get("sellPrice").asInt());
        assertEquals(1, out.get("slotMax").asInt());
        assertEquals(1, out.get("category").asInt()); // 1002357 / 1000000
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        ItemTool.ItemInfo info = new ItemTool.ItemInfo() {
            @Override public String getName(int id)       { return null; }
            @Override public int    getWholePrice(int id) { return 0; }
            @Override public short  getSlotMax(int id)    { return (short) 0; }
        };

        ItemTool tool = new ItemTool(info);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_missingId_throwsInvalidParams() {
        ItemTool.ItemInfo info = new ItemTool.ItemInfo() {
            @Override public String getName(int id)       { return null; }
            @Override public int    getWholePrice(int id) { return 0; }
            @Override public short  getSlotMax(int id)    { return (short) 0; }
        };

        ItemTool tool = new ItemTool(info);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        ItemTool.ItemInfo info = new ItemTool.ItemInfo() {
            @Override public String getName(int id)       { return "x"; }
            @Override public int    getWholePrice(int id) { return 0; }
            @Override public short  getSlotMax(int id)    { return (short) 0; }
        };
        assertEquals("cosmic.item.describe", new ItemTool(info).name());
    }
}
