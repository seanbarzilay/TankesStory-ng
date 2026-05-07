package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.MobMapIndex;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.util.HashMap;
import java.util.Map;

public class MobWhereTool implements Tool {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final MobMapIndex mobMaps;
    private final Map<Integer, String> mapNames;

    public MobWhereTool(MobMapIndex mobMaps, NameIndex names) {
        this.mobMaps = mobMaps;
        Map<Integer, String> built = new HashMap<>();
        for (NameIndex.Hit hit : names.search("", NameIndex.Kind.MAP, Integer.MAX_VALUE)) {
            built.put(hit.id(), hit.name());
        }
        this.mapNames = Map.copyOf(built);
    }

    @Override
    public String name() { return "cosmic.mob.where"; }

    @Override
    public String description() {
        return "List the maps a Cosmic monster spawns on (canonical Map.wz placements).";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode id = props.putObject("mob_id");
        id.put("type", "integer");
        id.put("description", "Mob ID (e.g. 3100101 for Sand Dwarf).");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("mob_id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("mob_id") || !args.get("mob_id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'mob_id'");
        }
        int mobId = args.get("mob_id").asInt();

        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode maps = out.putArray("maps");
        var ids = mobMaps.mapsFor(mobId);
        int total = ids.size();
        for (int i = 0; i < ids.size() && i < limit; i++) {
            int mapId = ids.get(i);
            ObjectNode entry = maps.addObject();
            entry.put("map_id", mapId);
            String mapName = mapNames.get(mapId);
            if (mapName != null) entry.put("map_name", mapName);
        }
        out.put("total", total);
        out.put("truncated", total > limit);
        return out;
    }
}
