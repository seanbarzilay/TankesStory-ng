package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.MobMapIndex;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobWhereToolTest {

    private MobWhereTool tool() {
        NameIndex names = new NameIndex();
        names.add(NameIndex.Kind.MAP, 100040000, "Pig Beach");
        names.add(NameIndex.Kind.MAP, 100040001, "Pig Park");
        names.add(NameIndex.Kind.MAP, 200000001, "Ellinia Tree");

        MobMapIndex mobMaps = MobMapIndex.loadFrom(names, mapId -> switch (mapId) {
            case 100040000 -> List.of(3100101);
            case 100040001 -> List.of(3100101, 3100102);
            case 200000001 -> List.of(2300100);
            default -> List.of();
        });

        return new MobWhereTool(mobMaps, names);
    }

    @Test
    void call_returnsMapsForMob() throws Exception {
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 3100101);
        JsonNode out = tool().call(args);

        assertEquals(2, out.get("total").asInt());
        assertEquals(2, out.get("maps").size());
        assertFalse(out.get("truncated").asBoolean());
        assertEquals(100040000, out.get("maps").get(0).get("map_id").asInt());
        assertEquals("Pig Beach", out.get("maps").get(0).get("map_name").asText());
    }

    @Test
    void call_unknownMob_returnsEmpty() throws Exception {
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 999999);
        JsonNode out = tool().call(args);
        assertEquals(0, out.get("total").asInt());
        assertEquals(0, out.get("maps").size());
    }

    @Test
    void call_truncatesAtLimit() throws Exception {
        NameIndex names = new NameIndex();
        for (int i = 0; i < 10; i++) {
            names.add(NameIndex.Kind.MAP, 100000000 + i, "Map " + i);
        }
        MobMapIndex mobMaps = MobMapIndex.loadFrom(names, mapId -> List.of(42));
        MobWhereTool t = new MobWhereTool(mobMaps, names);

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 42);
        args.put("limit", 3);
        JsonNode out = t.call(args);

        assertEquals(10, out.get("total").asInt());
        assertEquals(3, out.get("maps").size());
        assertTrue(out.get("truncated").asBoolean());
    }

    @Test
    void call_missingMobId_throws() {
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        assertThrows(Tool.ToolException.class, () -> tool().call(args));
    }

    @Test
    void call_omitsMapNameIfMissing() throws Exception {
        NameIndex names = new NameIndex();
        // Map 100040000 has NO entry in NameIndex (e.g. unnamed scratch map)
        MobMapIndex mobMaps = MobMapIndex.loadFrom(
                indexWith(Map.of(100040000, "Named")),
                mapId -> List.of(42));
        MobWhereTool t = new MobWhereTool(mobMaps, names);  // names is empty

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 42);
        JsonNode out = t.call(args);

        assertEquals(1, out.get("maps").size());
        assertFalse(out.get("maps").get(0).has("map_name"));
    }

    private static NameIndex indexWith(Map<Integer, String> mapEntries) {
        NameIndex n = new NameIndex();
        for (var e : mapEntries.entrySet()) {
            n.add(NameIndex.Kind.MAP, e.getKey(), e.getValue());
        }
        return n;
    }
}
