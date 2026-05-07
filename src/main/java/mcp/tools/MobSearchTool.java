package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.MobIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

public class MobSearchTool implements Tool {

    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final MobIndex index;

    public MobSearchTool(MobIndex index) {
        this.index = index;
    }

    @Override
    public String name() { return "cosmic.mob.search"; }

    @Override
    public String description() {
        return "Search Cosmic monsters by level range and (optionally) boss flag.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("min_level").put("type", "integer").put("minimum", 1).put("maximum", 999);
        props.putObject("max_level").put("type", "integer").put("minimum", 1).put("maximum", 999);
        ObjectNode boss = props.putObject("boss");
        boss.put("type", "boolean");
        boss.put("description", "Optional: true to return only bosses, false to exclude bosses, omit for both.");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("min_level").add("max_level");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("min_level") || !args.get("min_level").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'min_level'");
        }
        if (!args.has("max_level") || !args.get("max_level").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'max_level'");
        }
        int min = args.get("min_level").asInt();
        int max = args.get("max_level").asInt();
        if (max < min) {
            throw new ToolException(McpError.INVALID_PARAMS, "max_level must be >= min_level");
        }

        Boolean bossFilter = null;
        if (args.has("boss") && !args.get("boss").isNull()) {
            if (!args.get("boss").isBoolean()) {
                throw new ToolException(McpError.INVALID_PARAMS, "'boss' must be a boolean");
            }
            bossFilter = args.get("boss").asBoolean();
        }

        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode hits = out.putArray("hits");
        for (MobIndex.Entry e : index.search(min, max, bossFilter, limit)) {
            ObjectNode hn = hits.addObject();
            hn.put("id", e.id());
            hn.put("name", e.name());
            hn.put("level", e.level());
            hn.put("boss", e.boss());
        }
        return out;
    }
}
