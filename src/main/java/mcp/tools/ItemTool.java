package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.ItemInformationProvider;

public class ItemTool implements Tool {

    /**
     * Minimal seam over {@link ItemInformationProvider} so tests can inject a
     * lightweight stub without triggering the provider's static initialiser.
     */
    interface ItemInfo {
        String getName(int itemId);
        int getWholePrice(int itemId);
        short getSlotMax(int itemId);
    }

    private final ItemInfo info;

    /** Production constructor — wraps the real singleton. */
    public ItemTool() {
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        this.info = new ItemInfo() {
            @Override public String getName(int id)          { return iip.getName(id); }
            @Override public int    getWholePrice(int id)    { return iip.getWholePrice(id); }
            @Override public short  getSlotMax(int id)       { return iip.getSlotMax(null, id); }
        };
    }

    /** Test-friendly constructor — accepts an injected {@link ItemInfo}. */
    ItemTool(ItemInfo info) {
        this.info = info;
    }

    @Override
    public String name() { return "cosmic.item.describe"; }

    @Override
    public String description() { return "Describe a Cosmic item by ID (name, category, sell price, slot max)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode id = root.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.put("description", "Item ID (e.g. 1002357).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        String name = info.getName(id);
        if (name == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such item: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", name);
        out.put("category", id / 1_000_000);
        out.put("sellPrice", info.getWholePrice(id));
        out.put("slotMax", info.getSlotMax(id));
        return out;
    }
}
