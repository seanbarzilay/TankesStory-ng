package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void register_andLookup() {
        Tool fake = fakeTool("a.b");
        ToolRegistry reg = new ToolRegistry(List.of(fake));
        Optional<Tool> got = reg.find("a.b");
        assertTrue(got.isPresent());
        assertEquals("a.b", got.get().name());
    }

    @Test
    void list_returnsAllToolDescriptors() {
        ToolRegistry reg = new ToolRegistry(List.of(fakeTool("a.b"), fakeTool("c.d")));
        List<Tool.Descriptor> list = reg.list();
        assertEquals(2, list.size());
        assertEquals("a.b", list.get(0).name());
    }

    @Test
    void duplicateName_throws() {
        try {
            new ToolRegistry(List.of(fakeTool("a.b"), fakeTool("a.b")));
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    private Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc"; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return JsonRpc.MAPPER.createObjectNode(); }
        };
    }
}
