package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpDispatcherTest {

    @Test
    void initialize_returnsServerInfo() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNull(resp.error());
        assertEquals("cosmic-mcp", resp.result().get("serverInfo").get("name").asText());
    }

    @Test
    void toolsList_returnsRegistry() throws Exception {
        Tool fake = fakeTool("a.b");
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of(fake)));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertEquals(1, resp.result().get("tools").size());
        assertEquals("a.b", resp.result().get("tools").get(0).get("name").asText());
    }

    @Test
    void toolsCall_unknownName_returnsMethodNotFound() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"missing","arguments":{}}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNotNull(resp.error());
        assertEquals(McpError.METHOD_NOT_FOUND, resp.error().code());
    }

    @Test
    void toolsCall_knownName_returnsResult() throws Exception {
        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return ""; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return args; }
        };
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of(echo)));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"x":42}}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNull(resp.error());
        assertEquals(42, resp.result().get("content").get(0).get("json").get("x").asInt());
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"frobnicate"}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertEquals(McpError.METHOD_NOT_FOUND, resp.error().code());
    }

    private Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return JsonRpc.MAPPER.createObjectNode(); }
        };
    }
}
