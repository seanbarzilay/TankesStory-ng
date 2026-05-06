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

class QuestToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsScriptIfPresent() throws Exception {
        Path questDir = Files.createDirectories(scriptsRoot.resolve("quest"));
        Files.writeString(questDir.resolve("21010.js"), "// dummy");
        QuestTool tool = new QuestTool(scriptsRoot, id -> "Test Quest");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 21010);
        JsonNode out = tool.call(args);
        assertEquals("Test Quest", out.get("name").asText());
        assertTrue(out.get("scriptPath").asText().endsWith("21010.js"));
    }

    @Test
    void call_unknownQuest_throws() {
        QuestTool tool = new QuestTool(scriptsRoot, id -> null);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.quest.describe", new QuestTool(scriptsRoot, id -> null).name());
    }
}
