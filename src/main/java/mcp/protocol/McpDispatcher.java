package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);
    private static final String SERVER_NAME = "cosmic-mcp";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public McpDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public void shutdown() {
        running.set(false);
    }

    public JsonRpc.Response dispatch(JsonRpc.Request req) {
        if (!running.get()) {
            return JsonRpc.error(req.id(), McpError.shuttingDown());
        }
        try {
            return switch (req.method()) {
                case "initialize" -> JsonRpc.result(req.id(), buildInitializeResult());
                case "tools/list" -> JsonRpc.result(req.id(), buildToolsList());
                case "tools/call" -> dispatchToolCall(req);
                default -> JsonRpc.error(req.id(), McpError.methodNotFound(req.method()));
            };
        } catch (Tool.ToolException e) {
            return JsonRpc.error(req.id(), new McpError(e.code(), e.getMessage()));
        } catch (Exception e) {
            log.warn("dispatcher internal error for method={}", req.method(), e);
            return JsonRpc.error(req.id(), McpError.internal("internal error"));
        }
    }

    private ObjectNode buildInitializeResult() {
        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        return result;
    }

    private ObjectNode buildToolsList() {
        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = result.putArray("tools");
        for (Tool.Descriptor d : registry.list()) {
            ObjectNode n = arr.addObject();
            n.put("name", d.name());
            n.put("description", d.description());
            n.set("inputSchema", d.inputSchema());
        }
        return result;
    }

    private JsonRpc.Response dispatchToolCall(JsonRpc.Request req) throws Tool.ToolException {
        JsonNode params = req.params();
        if (params == null || !params.has("name")) {
            return JsonRpc.error(req.id(), McpError.invalidParams("missing tools/call name"));
        }
        String name = params.get("name").asText();
        Optional<Tool> tool = registry.find(name);
        if (tool.isEmpty()) {
            return JsonRpc.error(req.id(), McpError.methodNotFound(name));
        }
        JsonNode args = params.has("arguments") ? params.get("arguments") : JsonRpc.MAPPER.createObjectNode();
        JsonNode toolResult = tool.get().call(args);

        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", toolResult.toString());
        return JsonRpc.result(req.id(), result);
    }
}
