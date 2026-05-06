package mcp.tools;

import client.Skill;
import client.SkillFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

public class SkillTool implements Tool {

    @Override
    public String name() { return "cosmic.skill.describe"; }

    @Override
    public String description() { return "Describe a Cosmic skill by ID (job, max level, element)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode id = props.putObject("id");
        id.put("type", "integer");
        id.put("description", "Skill ID (e.g. 1121006).");
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
        Skill skill = SkillFactory.getSkill(id);
        if (skill == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such skill: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("job", skill.getId() / 10000);
        out.put("maxLevel", skill.getMaxLevel());
        out.put("element", String.valueOf(skill.getElement()));
        return out;
    }
}
