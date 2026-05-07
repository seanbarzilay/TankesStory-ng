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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRevertToolTest {

    @TempDir
    Path repoRoot;

    @BeforeEach
    void setup() throws Exception {
        Assumptions.assumeTrue(isGitOnPath(), "git not on PATH");
        run("git", "init");
        run("git", "config", "user.email", "t@t.t");
        run("git", "config", "user.name", "t");
        run("git", "config", "commit.gpgsign", "false");
        Path f = repoRoot.resolve("config.yaml");
        Files.writeString(f, "server:\n  port: 8484\n");
        run("git", "add", "config.yaml");
        run("git", "commit", "-m", "init");
    }

    @Test
    void call_revertsModifiedFile() throws Exception {
        Files.writeString(repoRoot.resolve("config.yaml"), "server:\n  port: 9999\n");
        GitRevertTool tool = new GitRevertTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.yaml");
        JsonNode out = tool.call(args);
        assertTrue(out.get("reverted").asBoolean());
        assertEquals("server:\n  port: 8484\n", Files.readString(repoRoot.resolve("config.yaml")));
    }

    @Test
    void call_disallowedPath_rejected() {
        GitRevertTool tool = new GitRevertTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/foo.java");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
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
