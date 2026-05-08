package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.bot.Bot;
import server.bot.BotManager;

import java.sql.SQLException;

public class BotDriveTool implements Tool {

    private final BotManager manager;
    private final AuditLog auditLog;

    public BotDriveTool(BotManager manager, AuditLog auditLog) {
        this.manager = manager;
        this.auditLog = auditLog;
    }

    @Override
    public String name() { return "cosmic.bot.drive"; }

    @Override
    public String description() {
        return "Set a player-bot's drive mode (IDLE, FOLLOW, GRIND) and optional target/filter.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("bot_id").put("type", "integer");
        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.putArray("enum").add("IDLE").add("FOLLOW").add("GRIND");
        props.putObject("target_char_id").put("type", "integer");
        props.putObject("mob_filter").put("type", "string");
        root.putArray("required").add("bot_id").add("mode");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("bot_id") || !args.get("bot_id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'bot_id'");
        }
        if (!args.has("mode") || !args.get("mode").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing/invalid 'mode'");
        }

        int botId = args.get("bot_id").asInt();
        String modeStr = args.get("mode").asText();

        Bot bot = manager.findById(botId);
        if (bot == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "unknown bot_id: " + botId);
        }

        Bot.Mode newMode;
        try {
            newMode = Bot.Mode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            throw new ToolException(McpError.INVALID_PARAMS,
                    "unknown mode: " + modeStr + " (expected IDLE|FOLLOW|GRIND)");
        }

        Integer targetCharId = null;
        if (args.has("target_char_id")) {
            JsonNode tc = args.get("target_char_id");
            if (!tc.isInt()) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid 'target_char_id'");
            }
            targetCharId = tc.asInt();
        }
        String mobFilter = null;
        if (args.has("mob_filter")) {
            JsonNode mf = args.get("mob_filter");
            if (!mf.isTextual()) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid 'mob_filter'");
            }
            mobFilter = mf.asText();
        }

        // Capture before-state for audit.
        ObjectNode beforeJson = JsonRpc.MAPPER.createObjectNode();
        beforeJson.put("mode", bot.mode().name());
        if (bot.targetCharId() != null) beforeJson.put("target_char_id", bot.targetCharId());
        else beforeJson.putNull("target_char_id");
        if (bot.mobFilter() != null) beforeJson.put("mob_filter", bot.mobFilter());
        else beforeJson.putNull("mob_filter");

        bot.setMode(newMode);
        if (targetCharId != null) bot.setTargetCharId(targetCharId);
        if (mobFilter != null) bot.setMobFilter(mobFilter);

        ObjectNode argsCopy = JsonRpc.MAPPER.createObjectNode();
        argsCopy.put("bot_id", botId);
        argsCopy.put("mode", newMode.name());
        if (targetCharId != null) argsCopy.put("target_char_id", targetCharId);
        if (mobFilter != null) argsCopy.put("mob_filter", mobFilter);
        AuditEntry entry = new AuditEntry(null, null, name(), argsCopy,
                "drive " + bot.name() + " -> " + newMode.name(), beforeJson, null, true);
        try {
            auditLog.insert(entry);
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("bot_id", botId);
        out.put("mode", newMode.name());
        out.put("ok", true);
        return out;
    }
}
