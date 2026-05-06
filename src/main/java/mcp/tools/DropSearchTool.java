package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.DropIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.util.List;

public class DropSearchTool implements Tool {

    private final DropIndex index;

    public DropSearchTool(DropIndex index) {
        this.index = index;
    }

    @Override
    public String name() { return "cosmic.drop.search"; }

    @Override
    public String description() { return "Find drops by mob_id or sources of a given item_id."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("mob_id").put("type", "integer");
        props.putObject("item_id").put("type", "integer");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        boolean hasMob = args.has("mob_id") && args.get("mob_id").isInt();
        boolean hasItem = args.has("item_id") && args.get("item_id").isInt();
        if (!hasMob && !hasItem) {
            throw new ToolException(McpError.INVALID_PARAMS, "provide mob_id or item_id");
        }
        if (hasMob && hasItem) {
            throw new ToolException(McpError.INVALID_PARAMS, "provide exactly one of mob_id or item_id");
        }
        List<DropIndex.Entry> entries = hasMob
                ? index.byMob(args.get("mob_id").asInt())
                : index.byItem(args.get("item_id").asInt());

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("drops");
        for (DropIndex.Entry e : entries) {
            ObjectNode n = arr.addObject();
            n.put("mobId", e.mobId());
            n.put("itemId", e.itemId());
            n.put("min", e.min());
            n.put("max", e.max());
            n.put("chance", e.chance());
            n.put("source", e.source());
        }
        return out;
    }
}
