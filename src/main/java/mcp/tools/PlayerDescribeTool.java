package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.util.Optional;

public class PlayerDescribeTool implements Tool {

    private final PlayerLookup lookup;

    public PlayerDescribeTool(PlayerLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String name() { return "cosmic.admin.player.describe"; }

    @Override
    public String description() { return "Describe a Cosmic player by name (online state preferred, falls back to DB)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("name").put("type", "string");
        root.putArray("required").add("name");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("name") || !args.get("name").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'name'");
        }
        String name = args.get("name").asText();
        Optional<PlayerLookup.Snapshot> opt = lookup.find(name);
        if (opt.isEmpty()) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such player: " + name);
        }
        PlayerLookup.Snapshot s = opt.get();
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("name", s.name());
        out.put("level", s.level());
        out.put("job", s.job());
        out.put("exp", s.exp());
        out.put("world", s.world());
        out.put("channel", s.channel());
        out.put("map", s.map());
        out.put("hp", s.hp());
        out.put("mp", s.mp());
        out.put("mesos", s.mesos());
        out.put("gmLevel", s.gmLevel());
        out.put("online", s.online());
        return out;
    }
}
