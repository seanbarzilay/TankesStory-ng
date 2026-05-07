package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.MobIndex;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobSearchToolTest {

    private MobIndex index() {
        NameIndex names = new NameIndex();
        names.add(NameIndex.Kind.MOB, 100100, "Snail");
        names.add(NameIndex.Kind.MOB, 200200, "Drake");
        names.add(NameIndex.Kind.MOB, 300300, "Big Boss");
        Map<Integer, MobIndex.MobMeta> meta = Map.of(
                100100, new MobIndex.MobMeta(2, false),
                200200, new MobIndex.MobMeta(33, false),
                300300, new MobIndex.MobMeta(33, true)
        );
        return MobIndex.loadFrom(names, meta::get);
    }

    @Test
    void call_filtersByLevelRange() throws Exception {
        MobSearchTool tool = new MobSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("min_level", 30);
        args.put("max_level", 35);
        JsonNode out = tool.call(args);
        assertEquals(2, out.get("hits").size());
    }

    @Test
    void call_bossFilter_excludesNonBosses() throws Exception {
        MobSearchTool tool = new MobSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("min_level", 30);
        args.put("max_level", 35);
        args.put("boss", true);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("hits").size());
        assertEquals("Big Boss", out.get("hits").get(0).get("name").asText());
    }

    @Test
    void call_missingMinLevel_throws() {
        MobSearchTool tool = new MobSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("max_level", 30);
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_invertedRange_throws() {
        MobSearchTool tool = new MobSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("min_level", 50);
        args.put("max_level", 30);
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_includesAllExpectedFields() throws Exception {
        MobSearchTool tool = new MobSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("min_level", 1);
        args.put("max_level", 100);
        JsonNode out = tool.call(args);
        JsonNode first = out.get("hits").get(0);
        assertTrue(first.has("id"));
        assertTrue(first.has("name"));
        assertTrue(first.has("level"));
        assertTrue(first.has("boss"));
    }
}
