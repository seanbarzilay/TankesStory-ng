package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobToolTest {

    @Test
    void call_known_returnsDescription() throws Exception {
        MobTool.MobLookup lookup = id -> id == 100100
                ? new MobTool.MobInfo(7, 100, 0, 15, false)
                : null;
        MobTool tool = new MobTool(lookup);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 100100);
        JsonNode out = tool.call(args);
        assertEquals(100100, out.get("id").asInt());
        assertEquals(7, out.get("level").asInt());
        assertEquals(100, out.get("maxHp").asInt());
        assertEquals(0, out.get("maxMp").asInt());
        assertEquals(15, out.get("exp").asInt());
        assertEquals(false, out.get("boss").asBoolean());
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        MobTool tool = new MobTool(id -> null);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_missingId_throwsInvalidParams() {
        MobTool tool = new MobTool(id -> null);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.mob.describe", new MobTool(id -> null).name());
    }
}
