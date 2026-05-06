package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class ScriptFinderTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final Path scriptsRoot;

    public ScriptFinderTool() {
        this(Path.of("scripts"));
    }

    public ScriptFinderTool(Path scriptsRoot) {
        this.scriptsRoot = scriptsRoot;
    }

    @Override
    public String name() { return "cosmic.script.find"; }

    @Override
    public String description() { return "Search Cosmic JS scripts (NPC, quest, reactor, etc.) for a substring."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("query");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("query") || !args.get("query").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'query'");
        }
        String query = args.get("query").asText();
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode matches = out.putArray("matches");
        if (!Files.isDirectory(scriptsRoot)) {
            return out;
        }
        try (Stream<Path> walker = Files.walk(scriptsRoot)) {
            for (Path p : (Iterable<Path>) walker::iterator) {
                if (matches.size() >= limit) break;
                if (!Files.isRegularFile(p)) continue;
                String fname = p.getFileName().toString();
                if (!fname.endsWith(".js")) continue;
                int line = 0;
                for (String content : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line++;
                    if (content.contains(query)) {
                        ObjectNode m = matches.addObject();
                        m.put("file", p.toString());
                        m.put("line", line);
                        m.put("snippet", content.length() > 200 ? content.substring(0, 200) : content);
                        if (matches.size() >= limit) break;
                    }
                }
            }
        } catch (IOException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "scan failed: " + e.getMessage());
        }
        return out;
    }
}
