package mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import mcp.edit.FileEditEngine;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.nio.file.Path;

public class ConfigEditTool implements Tool {

    private static final YAMLMapper YAML = new YAMLMapper();

    private final Path repoRoot;
    private final FileEditEngine engine;

    public ConfigEditTool(Path repoRoot) {
        this(repoRoot, new FileEditEngine());
    }

    ConfigEditTool(Path repoRoot, FileEditEngine engine) {
        this.repoRoot = repoRoot;
        this.engine = engine;
    }

    @Override
    public String name() { return "cosmic.config.edit"; }

    @Override
    public String description() { return "Edit config.yaml. Find-replace OR full content. Post-edit YAML parse check."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Must equal config.yaml.");
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
            resolved = PathSafety.resolveConfig(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        FileEditEngine.ContentValidator yamlCheck = content -> {
            try {
                YAML.readTree(content);
            } catch (JsonProcessingException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid YAML: " + e.getOriginalMessage());
            }
        };
        return engine.apply(resolved, input, args, yamlCheck);
    }
}
