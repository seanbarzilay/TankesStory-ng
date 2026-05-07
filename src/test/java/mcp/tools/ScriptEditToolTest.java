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

class ScriptEditToolTest {

    @TempDir
    Path repoRoot;

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.script.edit", new ScriptEditTool(repoRoot).name());
    }

    @Test
    void call_findReplace_succeeds() throws Exception {
        Files.createDirectories(repoRoot.resolve("scripts/npc"));
        Path f = repoRoot.resolve("scripts/npc/9201000.js");
        Files.writeString(f, "cm.gainItem(1, 1);\n");
        ScriptEditTool tool = new ScriptEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "scripts/npc/9201000.js");
        args.put("old_string", "1, 1");
        args.put("new_string", "2, 1");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("cm.gainItem(2, 1);\n", Files.readString(f));
    }

    @Test
    void call_fullContent_createsNewFile() throws Exception {
        Files.createDirectories(repoRoot.resolve("scripts/quest"));
        ScriptEditTool tool = new ScriptEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "scripts/quest/21999.js");
        args.put("content", "function start() {}\n");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertTrue(out.get("created").asBoolean());
        assertEquals("function start() {}\n", Files.readString(repoRoot.resolve("scripts/quest/21999.js")));
    }

    @Test
    void call_pathOutsideScripts_throwsInvalidParams() {
        ScriptEditTool tool = new ScriptEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/foo.js");
        args.put("content", "x");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("path not allowed"));
    }

    @Test
    void call_nonJsExtension_throwsInvalidParams() {
        ScriptEditTool tool = new ScriptEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "scripts/npc/x.txt");
        args.put("content", "x");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_dryRun_returnsDiffWithoutWriting() throws Exception {
        Files.createDirectories(repoRoot.resolve("scripts/npc"));
        Path f = repoRoot.resolve("scripts/npc/9201000.js");
        Files.writeString(f, "cm.gainItem(1, 1);\n");
        ScriptEditTool tool = new ScriptEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "scripts/npc/9201000.js");
        args.put("old_string", "1, 1");
        args.put("new_string", "2, 1");
        args.put("dry_run", true);
        JsonNode out = tool.call(args);
        assertEquals("preview", out.get("mode").asText());
        assertEquals("cm.gainItem(1, 1);\n", Files.readString(f), "file untouched on dry_run");
    }
}
