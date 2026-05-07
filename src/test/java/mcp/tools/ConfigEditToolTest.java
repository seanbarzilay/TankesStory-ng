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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditToolTest {

    @TempDir
    Path repoRoot;

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.config.edit", new ConfigEditTool(repoRoot).name());
    }

    @Test
    void call_findReplace_succeeds() throws Exception {
        Files.writeString(repoRoot.resolve("config.yaml"), "server:\n  port: 8484\n");
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.yaml");
        args.put("old_string", "8484");
        args.put("new_string", "8485");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("server:\n  port: 8485\n", Files.readString(repoRoot.resolve("config.yaml")));
    }

    @Test
    void call_invalidYaml_throwsAndDoesNotWrite() throws IOException {
        Files.writeString(repoRoot.resolve("config.yaml"), "server:\n  port: 8484\n");
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.yaml");
        args.put("content", "not valid: [yaml");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("invalid YAML"));
        assertEquals("server:\n  port: 8484\n", Files.readString(repoRoot.resolve("config.yaml")), "file unchanged");
    }

    @Test
    void call_pathNotConfig_throws() {
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.local.yaml");
        args.put("content", "x: 1\n");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
