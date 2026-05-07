package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

public class MobTool implements Tool {

    /** Stats subset needed by this tool. */
    record MobInfo(int level, int maxHp, int maxMp, int exp, boolean boss) {}

    /** Test seam — production wraps LifeFactory.getMonster, tests inject a stub. */
    interface MobLookup {
        MobInfo find(int id);
    }

    private final MobLookup lookup;
    private final IntFunction<String> nameLookup;

    /** Production constructor — wraps the real LifeFactory and a NameIndex-backed name resolver. */
    public MobTool(NameIndex names) {
        this(
                id -> {
                    Monster m = LifeFactory.getMonster(id);
                    if (m == null) return null;
                    return new MobInfo(m.getLevel(), m.getMaxHp(), m.getMaxMp(), m.getExp(), m.isBoss());
                },
                buildNameResolver(names)
        );
    }

    /** Test-friendly constructor. */
    MobTool(MobLookup lookup, IntFunction<String> nameLookup) {
        this.lookup = lookup;
        this.nameLookup = nameLookup;
    }

    private static IntFunction<String> buildNameResolver(NameIndex names) {
        Map<Integer, String> idToName = new HashMap<>();
        for (NameIndex.Hit hit : names.search("", NameIndex.Kind.MOB, Integer.MAX_VALUE)) {
            idToName.put(hit.id(), hit.name());
        }
        Map<Integer, String> snapshot = Map.copyOf(idToName);
        return snapshot::get;
    }

    @Override
    public String name() { return "cosmic.mob.describe"; }

    @Override
    public String description() { return "Describe a Cosmic monster by ID (name, level, max HP/MP, EXP, boss flag)."; }

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
        String name = nameLookup.apply(id);
        if (name != null) out.put("name", name);
        out.put("level", info.level());
        out.put("maxHp", info.maxHp());
        out.put("maxMp", info.maxMp());
        out.put("exp", info.exp());
        out.put("boss", info.boss());
        return out;
    }
}
