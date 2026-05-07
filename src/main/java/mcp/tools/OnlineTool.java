package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;

import java.util.List;

public class OnlineTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final PlayerLookup lookup;

    public OnlineTool(PlayerLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String name() { return "cosmic.admin.online"; }

    @Override
    public String description() { return "List online Cosmic players, optionally filtered by world/channel/map/name."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("world").put("type", "integer");
        props.putObject("channel").put("type", "integer");
        props.putObject("map").put("type", "integer");
        props.putObject("name_substring").put("type", "string");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        Integer world = args.has("world") && args.get("world").isInt() ? args.get("world").asInt() : null;
        Integer channel = args.has("channel") && args.get("channel").isInt() ? args.get("channel").asInt() : null;
        Integer map = args.has("map") && args.get("map").isInt() ? args.get("map").asInt() : null;
        String nameSub = args.has("name_substring") && args.get("name_substring").isTextual() ? args.get("name_substring").asText() : null;
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        List<PlayerLookup.Snapshot> hits = lookup.online(world, channel, map, nameSub, limit);
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode players = out.putArray("players");
        for (PlayerLookup.Snapshot s : hits) {
            ObjectNode p = players.addObject();
            p.put("name", s.name());
            p.put("level", s.level());
            p.put("job", s.job());
            p.put("world", s.world());
            p.put("channel", s.channel());
            p.put("map", s.map());
            p.put("hp", s.hp());
            p.put("mp", s.mp());
        }
        out.put("total", hits.size());
        return out;
    }
}
