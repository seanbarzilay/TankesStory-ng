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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JavaCodeSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    // Matches enum constants like:  USE_ITEM((short) 0x6C),  or  USE_ITEM(0x6C),  or USE_ITEM(108)
    private static final Pattern OPCODE_DECL = Pattern.compile(
            "(?m)^\\s*([A-Z_][A-Z0-9_]*)\\s*\\(\\s*(?:\\(\\s*short\\s*\\))?\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)");

    private final Path srcRoot;

    public JavaCodeSearchTool() {
        this(Path.of("src/main/java"));
    }

    public JavaCodeSearchTool(Path srcRoot) {
        this.srcRoot = srcRoot;
    }

    @Override
    public String name() { return "cosmic.code.search"; }

    @Override
    public String description() { return "Search Cosmic Java sources. kind=opcode resolves a hex/int opcode to its enum name and finds references."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.putArray("enum").add("text").add("opcode");
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
        String kind = args.has("kind") ? args.get("kind").asText() : "text";
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();

        String searchTerm;
        if ("opcode".equals(kind)) {
            int target;
            try { target = Integer.decode(query); }
            catch (NumberFormatException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "opcode must be hex (0x6C) or int");
            }
            String resolved = resolveOpcodeName(target);
            if (resolved == null) {
                throw new ToolException(McpError.INVALID_PARAMS, "no opcode constant matches " + query);
            }
            out.put("opcodeName", resolved);
            searchTerm = resolved;
        } else {
            searchTerm = query;
        }

        ArrayNode matches = out.putArray("matches");
        try (Stream<Path> walker = Files.walk(srcRoot)) {
            for (Path p : (Iterable<Path>) walker::iterator) {
                if (matches.size() >= limit) break;
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".java")) continue;
                int line = 0;
                for (String content : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line++;
                    if (content.contains(searchTerm)) {
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

    private String resolveOpcodeName(int target) throws ToolException {
        Path[] candidates = {
                srcRoot.resolve("net/opcodes/RecvOpcode.java"),
                srcRoot.resolve("net/opcodes/SendOpcode.java")
        };
        for (Path c : candidates) {
            if (!Files.exists(c)) continue;
            try {
                String body = Files.readString(c, StandardCharsets.UTF_8);
                Matcher m = OPCODE_DECL.matcher(body);
                while (m.find()) {
                    int v;
                    try { v = Integer.decode(m.group(2)); }
                    catch (NumberFormatException e) { continue; }
                    if (v == target) return m.group(1);
                }
            } catch (IOException e) {
                throw new ToolException(McpError.INTERNAL_ERROR, "opcode read failed: " + e.getMessage());
            }
        }
        return null;
    }
}
