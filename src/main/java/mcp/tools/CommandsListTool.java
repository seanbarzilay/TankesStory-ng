package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.CommandCatalog;
import mcp.protocol.JsonRpc;

import java.util.List;

public class CommandsListTool implements Tool {

    private final CommandCatalog catalog;

    public CommandsListTool(CommandCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() { return "cosmic.admin.commands.list"; }

    @Override
    public String description() { return "List Cosmic in-game @-commands. Filter by substring or GM level."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("filter_substring").put("type", "string");
        props.putObject("gm_level").put("type", "integer").put("minimum", 0).put("maximum", 6);
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        String filter = args.has("filter_substring") && args.get("filter_substring").isTextual()
                ? args.get("filter_substring").asText() : null;
        Integer gmLevel = args.has("gm_level") && args.get("gm_level").isInt()
                ? args.get("gm_level").asInt() : null;
        List<CommandCatalog.Entry> entries = catalog.list(filter, gmLevel);
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("commands");
        for (CommandCatalog.Entry e : entries) {
            ObjectNode n = arr.addObject();
            n.put("name", e.name());
            n.put("gm_level", e.gmLevel());
            n.put("syntax", "@" + e.name());
            n.put("description", e.description());
        }
        return out;
    }
}
