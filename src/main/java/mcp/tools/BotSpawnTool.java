package mcp.tools;

import client.bot.BotFactory;
import client.bot.BotPreset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.bot.Bot;
import server.bot.BotManager;

import java.sql.SQLException;

public class BotSpawnTool implements Tool {

    private final BotFactory factory;
    private final BotManager manager;
    private final AuditLog auditLog;

    public BotSpawnTool(BotFactory factory, BotManager manager, AuditLog auditLog) {
        this.factory = factory;
        this.manager = manager;
        this.auditLog = auditLog;
    }

    @Override
    public String name() { return "cosmic.bot.spawn"; }

    @Override
    public String description() {
        return "Spawn an in-process player-bot at the given world/channel/map/position.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("world").put("type", "integer");
        props.putObject("channel").put("type", "integer");
        props.putObject("map").put("type", "integer");
        props.putObject("x").put("type", "integer");
        props.putObject("y").put("type", "integer");
        root.putArray("required").add("world").add("channel").add("map").add("x").add("y");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("world") || !args.get("world").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'world'");
        }
        if (!args.has("channel") || !args.get("channel").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'channel'");
        }
        if (!args.has("map") || !args.get("map").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'map'");
        }
        if (!args.has("x") || !args.get("x").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'x'");
        }
        if (!args.has("y") || !args.get("y").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'y'");
        }

        int world = args.get("world").asInt();
        int channel = args.get("channel").asInt();
        int map = args.get("map").asInt();
        int x = args.get("x").asInt();
        int y = args.get("y").asInt();

        Bot bot;
        try {
            bot = factory.spawn(world, channel, map, x, y, BotPreset.BEGINNER_LV30);
        } catch (BotFactory.DisabledException e) {
            throw new ToolException(McpError.INVALID_REQUEST, "bots disabled");
        } catch (BotManager.AtCapException e) {
            throw new ToolException(McpError.INVALID_REQUEST, e.getMessage());
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("bot_id", bot.id());
        out.put("name", bot.name());

        ObjectNode argsCopy = JsonRpc.MAPPER.createObjectNode();
        argsCopy.put("world", world).put("channel", channel).put("map", map).put("x", x).put("y", y);
        AuditEntry entry = new AuditEntry(null, null, name(), argsCopy,
                "spawned " + bot.name() + " (id=" + bot.id() + ")", null, null, true);
        try {
            auditLog.insert(entry);
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
        }
        return out;
    }
}
