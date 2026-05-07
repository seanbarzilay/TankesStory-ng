package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffToolTest {

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
    void call_noChanges_returnsEmptyDiff() throws Exception {
        GitDiffTool tool = new GitDiffTool(repoRoot);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals("", out.get("diff").asText());
    }

    @Test
    void call_modifiedScript_returnsDiff() throws Exception {
        Files.writeString(repoRoot.resolve("scripts/npc/9201000.js"), "var x = 2;\n");
        GitDiffTool tool = new GitDiffTool(repoRoot);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        String diff = out.get("diff").asText();
        assertTrue(diff.contains("-var x = 1;"));
        assertTrue(diff.contains("+var x = 2;"));
    }

    @Test
    void call_specificPath_scopedDiff() throws Exception {
        Files.writeString(repoRoot.resolve("scripts/npc/9201000.js"), "var x = 2;\n");
        GitDiffTool tool = new GitDiffTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "scripts/npc/9201000.js");
        JsonNode out = tool.call(args);
        assertTrue(out.get("diff").asText().contains("scripts/npc/9201000.js"));
    }

    @Test
    void call_disallowedPath_throws() {
        GitDiffTool tool = new GitDiffTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/foo.java");
        Tool.ToolException ex = org.junit.jupiter.api.Assertions.assertThrows(
                Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
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
