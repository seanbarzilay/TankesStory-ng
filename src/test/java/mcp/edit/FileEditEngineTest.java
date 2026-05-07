package mcp.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEditEngineTest {

    @TempDir
    Path repoRoot;

    private final FileEditEngine engine = new FileEditEngine();

    @Test
    void apply_findReplace_writesAndReturnsDiff() throws Exception {
        Path file = repoRoot.resolve("a.js");
        Files.writeString(file, "var x = 1;\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "x = 1");
        args.put("new_string", "x = 2");
        JsonNode out = engine.apply(file, "a.js", args, null);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("var x = 2;\n", Files.readString(file));
        assertTrue(out.get("diff").asText().contains("-var x = 1;"));
        assertFalse(out.get("created").asBoolean());
    }

    @Test
    void apply_dryRun_returnsDiffWithoutWriting() throws Exception {
        Path file = repoRoot.resolve("a.js");
        Files.writeString(file, "abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "abc");
        args.put("new_string", "xyz");
        args.put("dry_run", true);
        JsonNode out = engine.apply(file, "a.js", args, null);
        assertEquals("preview", out.get("mode").asText());
        assertEquals("abc\n", Files.readString(file));
        assertTrue(out.get("diff").asText().contains("-abc"));
    }

    @Test
    void apply_fullContent_createsNewFile() throws Exception {
        Path file = repoRoot.resolve("new.js");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "new.js");
        args.put("content", "var y = 7;\n");
        JsonNode out = engine.apply(file, "new.js", args, null);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("var y = 7;\n", Files.readString(file));
        assertTrue(out.get("created").asBoolean());
    }

    @Test
    void apply_bothShapes_throws() throws IOException {
        Files.writeString(repoRoot.resolve("a.js"), "abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "abc");
        args.put("new_string", "xyz");
        args.put("content", "ignored");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("a.js"), "a.js", args, null));
        assertEquals(-32602, ex.code());
    }

    @Test
    void apply_neither_throws() {
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("a.js"), "a.js", args, null));
        assertEquals(-32602, ex.code());
    }

    @Test
    void apply_findReplace_oldStringMissing_throws() throws IOException {
        Files.writeString(repoRoot.resolve("a.js"), "abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "zzz");
        args.put("new_string", "yyy");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("a.js"), "a.js", args, null));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void apply_findReplace_multipleMatchesWithoutReplaceAll_throws() throws IOException {
        Files.writeString(repoRoot.resolve("a.js"), "abc abc abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "abc");
        args.put("new_string", "xyz");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("a.js"), "a.js", args, null));
        assertTrue(ex.getMessage().contains("matches"));
    }

    @Test
    void apply_findReplace_replaceAll_succeeds() throws Exception {
        Files.writeString(repoRoot.resolve("a.js"), "abc abc abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "abc");
        args.put("new_string", "x");
        args.put("replace_all", true);
        engine.apply(repoRoot.resolve("a.js"), "a.js", args, null);
        assertEquals("x x x\n", Files.readString(repoRoot.resolve("a.js")));
    }

    @Test
    void apply_findReplace_onMissingFile_throws() {
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "missing.js");
        args.put("old_string", "abc");
        args.put("new_string", "xyz");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("missing.js"), "missing.js", args, null));
        assertTrue(ex.getMessage().contains("no such file"));
    }

    @Test
    void apply_postValidator_rejectsBadContent() throws IOException {
        Files.writeString(repoRoot.resolve("a.js"), "abc\n");
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "a.js");
        args.put("old_string", "abc");
        args.put("new_string", "BAD");
        FileEditEngine.ContentValidator deny = c -> {
            throw new Tool.ToolException(-32602, "validator says no: " + c.trim());
        };
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> engine.apply(repoRoot.resolve("a.js"), "a.js", args, deny));
        assertTrue(ex.getMessage().contains("validator says no"));
        assertEquals("abc\n", Files.readString(repoRoot.resolve("a.js")), "file unchanged after validator rejection");
    }
}
