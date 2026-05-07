package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.FileEditEngine;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.nio.file.Path;

public class DropsEditSqlTool implements Tool {

    private final Path repoRoot;
    private final FileEditEngine engine;

    public DropsEditSqlTool(Path repoRoot) {
        this(repoRoot, new FileEditEngine());
    }

    DropsEditSqlTool(Path repoRoot, FileEditEngine engine) {
        this.repoRoot = repoRoot;
        this.engine = engine;
    }

    @Override
    public String name() { return "cosmic.drops.edit_sql"; }

    @Override
    public String description() { return "Edit a drop-data SQL file (drop_data, drop_data_global, reactordrops)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean");
        props.putObject("content").put("type", "string");
        props.putObject("dry_run").put("type", "boolean");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        Path resolved;
        try {
            resolved = PathSafety.resolveDrops(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        return engine.apply(resolved, input, args, null);
    }
}
