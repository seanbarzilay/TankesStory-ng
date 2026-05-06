package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.quest.Quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntFunction;

public class QuestTool implements Tool {

    private final Path scriptsRoot;
    private final IntFunction<String> nameLookup;

    /** Production constructor. */
    public QuestTool() {
        this(Path.of("scripts"), id -> {
            try {
                Quest q = Quest.getInstance(id);
                return q == null ? null : q.getName();
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Test-friendly constructor. */
    QuestTool(Path scriptsRoot, IntFunction<String> nameLookup) {
        this.scriptsRoot = scriptsRoot;
        this.nameLookup = nameLookup;
    }

    @Override
    public String name() { return "cosmic.quest.describe"; }

    @Override
    public String description() { return "Describe a Cosmic quest by ID (name + attached script)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode id = root.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.put("description", "Quest ID (e.g. 21010).");
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
        String name = nameLookup.apply(id);
        if (name == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such quest: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", name);
        Path script = scriptsRoot.resolve("quest").resolve(id + ".js");
        if (Files.exists(script)) {
            out.put("scriptPath", script.toString());
        } else {
            out.putNull("scriptPath");
        }
        return out;
    }
}
