package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

public class NameSearchTool implements Tool {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 50;

    private final NameIndex index;

    public NameSearchTool(NameIndex index) {
        this.index = index;
    }

    @Override
    public String name() { return "cosmic.name.search"; }

    @Override
    public String description() { return "Fuzzy-search Cosmic entity names (item/mob/map/npc/skill)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "Substring (case-insensitive).");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.put("description", "Optional: item|mob|map|npc|skill.");
        kind.putArray("enum").add("item").add("mob").add("map").add("npc").add("skill");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("query");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("query") || !args.get("query").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'query'");
        }
        String query = args.get("query").asText();
        NameIndex.Kind filter = null;
        if (args.has("kind") && !args.get("kind").isNull()) {
            String k = args.get("kind").asText().toUpperCase();
            try {
                filter = NameIndex.Kind.valueOf(k);
            } catch (IllegalArgumentException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "unknown kind: " + k);
            }
        }
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode hits = out.putArray("hits");
        for (NameIndex.Hit h : index.search(query, filter, limit)) {
            ObjectNode hn = hits.addObject();
            hn.put("kind", h.kind().name());
            hn.put("id", h.id());
            hn.put("name", h.name());
        }
        return out;
    }
}
