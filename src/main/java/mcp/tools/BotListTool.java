package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.bot.Bot;
import server.bot.BotManager;

import java.util.List;

public class BotListTool implements Tool {

    private final BotManager manager;

    public BotListTool(BotManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() { return "cosmic.bot.list"; }

    @Override
    public String description() {
        return "List active in-process player-bots, optionally filtered by world.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("world").put("type", "integer");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        Integer worldFilter = null;
        if (args != null && args.has("world")) {
            JsonNode w = args.get("world");
            if (!w.isInt()) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid 'world'");
            }
            worldFilter = w.asInt();
        }

        List<Bot> bots = (worldFilter != null)
                ? manager.listInWorld(worldFilter)
                : manager.activeBots();

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("bots");
        for (Bot bot : bots) {
            ObjectNode entry = arr.addObject();
            entry.put("bot_id", bot.id());
            entry.put("name", bot.name());
            entry.put("mode", bot.mode().name());
            if (bot.targetCharId() != null) entry.put("target_char_id", bot.targetCharId());
            else entry.putNull("target_char_id");
            entry.put("world", bot.world());
        }
        return out;
    }
}
