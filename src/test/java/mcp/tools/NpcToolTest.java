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

class NpcToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsAttachedScript() throws Exception {
        Path npcDir = Files.createDirectories(scriptsRoot.resolve("npc"));
        Files.writeString(npcDir.resolve("9201000.js"), "// dummy");
        NpcTool tool = new NpcTool(scriptsRoot, id -> "Mr Smith");

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9201000);
        JsonNode out = tool.call(args);
        assertEquals("Mr Smith", out.get("name").asText());
        assertTrue(out.get("scriptPath").asText().endsWith("9201000.js"));
    }

    @Test
    void call_noScript_returnsNull() throws Exception {
        NpcTool tool = new NpcTool(scriptsRoot, id -> "Anonymous");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999998);
        JsonNode out = tool.call(args);
        assertEquals("Anonymous", out.get("name").asText());
        assertTrue(out.get("scriptPath").isNull());
    }

    @Test
    void call_unknownNpc_throws() {
        NpcTool tool = new NpcTool(scriptsRoot, id -> null);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.npc.describe", new NpcTool(scriptsRoot, id -> null).name());
    }
}
