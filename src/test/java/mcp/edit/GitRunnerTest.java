package mcp.edit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRunnerTest {

    @TempDir
    Path repoRoot;

    @BeforeEach
    void initRepo() throws Exception {
        Assumptions.assumeTrue(isGitOnPath(), "git not on PATH — GitRunnerTest skipped");
        run("git", "init");
        run("git", "config", "user.email", "t@t.t");
        run("git", "config", "user.name", "t");
        run("git", "config", "commit.gpgsign", "false");
    }

    @Test
    void run_versionExitsZeroAndStdoutContainsGit() throws Exception {
        GitRunner runner = new GitRunner(repoRoot);
        GitRunner.Result r = runner.run("--version");
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().toLowerCase().contains("git"));
    }

    @Test
    void run_unknownCommandExitsNonZero() throws Exception {
        GitRunner runner = new GitRunner(repoRoot);
        GitRunner.Result r = runner.run("nope-command-does-not-exist");
        assertNotEquals(0, r.exitCode());
    }

    private static boolean isGitOnPath() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(repoRoot.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("setup cmd failed: " + String.join(" ", cmd));
    }
}
