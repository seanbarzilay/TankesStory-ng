package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigInspectTool implements Tool {

    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)\\]");

    private final Path configPath;

    public ConfigInspectTool() {
        this(Path.of("config.yaml"));
    }

    public ConfigInspectTool(Path configPath) {
        this.configPath = configPath;
    }

    @Override
    public String name() { return "cosmic.config.get"; }

    @Override
    public String description() { return "Read a value from config.yaml by dotted path (supports [index])."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String path = args.get("path").asText();
        JsonNode root;
        try {
            root = new YAMLMapper().readTree(Files.readString(configPath));
        } catch (IOException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "config read failed: " + e.getMessage());
        }
        JsonNode cur = root;
        Matcher m = SEGMENT.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                cur = cur.path(m.group(1));
            } else {
                cur = cur.path(Integer.parseInt(m.group(2)));
            }
            if (cur.isMissingNode()) {
                throw new ToolException(McpError.INVALID_PARAMS, "no such path: " + path);
            }
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.set("value", cur);
        return out;
    }
}
