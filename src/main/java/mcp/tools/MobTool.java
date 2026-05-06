package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.life.LifeFactory;
import server.life.Monster;

public class MobTool implements Tool {

    /** Stats subset needed by this tool. */
    record MobInfo(int level, int maxHp, int maxMp, int exp, boolean boss) {}

    /** Test seam — production wraps LifeFactory.getMonster, tests inject a stub. */
    interface MobLookup {
        MobInfo find(int id);
    }

    private final MobLookup lookup;

    /** Production constructor — wraps the real LifeFactory. */
    public MobTool() {
        this(id -> {
            Monster m = LifeFactory.getMonster(id);
            if (m == null) return null;
            return new MobInfo(m.getLevel(), m.getMaxHp(), m.getMaxMp(), m.getExp(), m.isBoss());
        });
    }

    /** Test-friendly constructor. */
    MobTool(MobLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String name() { return "cosmic.mob.describe"; }

    @Override
    public String description() { return "Describe a Cosmic monster by ID (level, max HP/MP, EXP, boss flag)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode id = root.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.put("description", "Mob ID (e.g. 100100).");
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
        MobInfo info = lookup.find(id);
        if (info == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such mob: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("level", info.level());
        out.put("maxHp", info.maxHp());
        out.put("maxMp", info.maxMp());
        out.put("exp", info.exp());
        out.put("boss", info.boss());
        return out;
    }
}
