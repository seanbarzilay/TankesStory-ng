package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigInspectToolTest {

    @TempDir
    Path tmp;

    private Path writeYaml(String yaml) throws IOException {
        Path p = tmp.resolve("config.yaml");
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void call_simpleScalar() throws Exception {
        Path p = writeYaml("server:\n  port: 8484\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "server.port");
        JsonNode out = tool.call(args);
        assertEquals(8484, out.get("value").asInt());
    }

    @Test
    void call_arrayIndex() throws Exception {
        Path p = writeYaml("worlds:\n  - exp_rate: 10\n  - exp_rate: 100\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "worlds[1].exp_rate");
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("value").asInt());
    }

    @Test
    void call_unknownPath_throws() throws Exception {
        Path p = writeYaml("server:\n  port: 8484\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "server.nonexistent");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
