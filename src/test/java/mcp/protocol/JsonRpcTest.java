package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonRpcTest {

    private final ObjectMapper mapper = JsonRpc.MAPPER;

    @Test
    void parseRequest_validToolsCall() throws Exception {
        String json = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x","arguments":{"a":1}}}""";
        JsonRpc.Request req = JsonRpc.parseRequest(json);
        assertEquals("2.0", req.jsonrpc());
        assertEquals(7, req.id().asInt());
        assertEquals("tools/call", req.method());
        assertEquals(1, req.params().get("arguments").get("a").asInt());
    }

    @Test
    void parseRequest_missingMethod_throws() {
        String json = """
                {"jsonrpc":"2.0","id":1}""";
        try {
            JsonRpc.parseRequest(json);
            throw new AssertionError("expected exception");
        } catch (JsonRpc.ParseException e) {
            // expected
        }
    }

    @Test
    void result_serializesCorrectly() throws Exception {
        JsonRpc.Response resp = JsonRpc.result(mapper.readTree("3"), mapper.valueToTree(42));
        String json = mapper.writeValueAsString(resp);
        JsonNode parsed = mapper.readTree(json);
        assertEquals("2.0", parsed.get("jsonrpc").asText());
        assertEquals(3, parsed.get("id").asInt());
        assertEquals(42, parsed.get("result").asInt());
        assertNull(parsed.get("error"));
    }

    @Test
    void error_serializesCorrectly() throws Exception {
        JsonRpc.Response resp = JsonRpc.error(mapper.readTree("3"), McpError.invalidParams("bad"));
        String json = mapper.writeValueAsString(resp);
        JsonNode parsed = mapper.readTree(json);
        assertEquals(-32602, parsed.get("error").get("code").asInt());
        assertEquals("bad", parsed.get("error").get("message").asText());
        assertNull(parsed.get("result"));
    }
}
