package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitCommitToolTest {

    @TempDir
    Path repoRoot;

    @BeforeEach
    void setup() throws Exception {
        Assumptions.assumeTrue(isGitOnPath(), "git not on PATH");
        run("git", "init");
        run("git", "config", "user.email", "t@t.t");
        run("git", "config", "user.name", "t");
        run("git", "config", "commit.gpgsign", "false");
        Path scripts = repoRoot.resolve("scripts/npc");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("9201000.js"), "var x = 1;\n");
        run("git", "add", "scripts/npc/9201000.js");
        run("git", "commit", "-m", "init");
    }

    @Test
    void call_commitsChangedFile() throws Exception {
        Files.writeString(repoRoot.resolve("scripts/npc/9201000.js"), "var x = 2;\n");
        GitCommitTool tool = new GitCommitTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        ArrayNode paths = args.putArray("paths");
        paths.add("scripts/npc/9201000.js");
        args.put("message", "tweak");
        JsonNode out = tool.call(args);
        assertTrue(out.get("sha").asText().length() >= 7);
        assertEquals(1, out.get("files_committed").size());
    }

    @Test
    void call_disallowedPath_rejected() {
        GitCommitTool tool = new GitCommitTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        ArrayNode paths = args.putArray("paths");
        paths.add("src/main/foo.java");
        args.put("message", "x");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_emptyMessage_rejected() {
        GitCommitTool tool = new GitCommitTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        ArrayNode paths = args.putArray("paths");
        paths.add("scripts/npc/9201000.js");
        args.put("message", "   ");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_emptyPathsArray_rejected() {
        GitCommitTool tool = new GitCommitTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.putArray("paths");
        args.put("message", "x");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    private static boolean isGitOnPath() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(repoRoot.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("setup cmd failed");
    }
}
