package mcp.edit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathSafetyTest {

    @TempDir
    Path repoRoot;

    @Test
    void resolveScript_validJsUnderScripts_succeeds() throws Exception {
        Files.createDirectories(repoRoot.resolve("scripts/npc"));
        Path resolved = PathSafety.resolveScript(repoRoot, "scripts/npc/9201000.js");
        assertEquals(repoRoot.resolve("scripts/npc/9201000.js").normalize(), resolved);
    }

    @Test
    void resolveScript_nonJsExtension_throws() {
        PathSafety.PathException ex = assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveScript(repoRoot, "scripts/npc/9201000.txt"));
        assertEquals("path not allowed: scripts/npc/9201000.txt", ex.getMessage());
    }

    @Test
    void resolveScript_outsideScriptsDir_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveScript(repoRoot, "src/main/foo.js"));
    }

    @Test
    void resolveScript_dotDotEscape_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveScript(repoRoot, "scripts/../../etc/passwd"));
    }

    @Test
    void resolveConfig_exact_succeeds() throws Exception {
        Path resolved = PathSafety.resolveConfig(repoRoot, "config.yaml");
        assertEquals(repoRoot.resolve("config.yaml").normalize(), resolved);
    }

    @Test
    void resolveConfig_anythingElse_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveConfig(repoRoot, "config.local.yaml"));
    }

    @Test
    void resolveDrops_validKnownFile_succeeds() throws Exception {
        Path resolved = PathSafety.resolveDrops(repoRoot, "src/main/resources/db/data/152-drop-data.sql");
        assertEquals(repoRoot.resolve("src/main/resources/db/data/152-drop-data.sql").normalize(), resolved);
    }

    @Test
    void resolveDrops_unknownDataFile_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveDrops(repoRoot, "src/main/resources/db/data/161-admin-data.sql"));
    }

    @Test
    void resolveAny_acceptsAnyAllowedSurface() throws Exception {
        assertEquals(repoRoot.resolve("config.yaml").normalize(),
                PathSafety.resolveAny(repoRoot, "config.yaml"));
        assertEquals(repoRoot.resolve("scripts/npc/x.js").normalize(),
                PathSafety.resolveAny(repoRoot, "scripts/npc/x.js"));
        assertEquals(repoRoot.resolve("src/main/resources/db/data/152-drop-data.sql").normalize(),
                PathSafety.resolveAny(repoRoot, "src/main/resources/db/data/152-drop-data.sql"));
    }

    @Test
    void resolveAny_disallowed_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveAny(repoRoot, "src/main/java/foo.java"));
    }

    @Test
    void resolveScript_symlinkEscapingRepo_throws(@TempDir Path outsideRoot) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(supportsSymlinks(repoRoot),
                "symlinks not supported on this filesystem");
        Files.createDirectories(repoRoot.resolve("scripts/npc"));
        Path target = outsideRoot.resolve("evil.js");
        Files.writeString(target, "/* outside */\n");
        Files.createSymbolicLink(repoRoot.resolve("scripts/npc/escape.js"), target);
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveScript(repoRoot, "scripts/npc/escape.js"));
    }

    private static boolean supportsSymlinks(Path dir) {
        try {
            Path probe = dir.resolve("__symlink_probe__");
            Path tgt = dir.resolve("__symlink_target__");
            Files.writeString(tgt, "x");
            Files.createSymbolicLink(probe, tgt);
            Files.deleteIfExists(probe);
            Files.deleteIfExists(tgt);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
