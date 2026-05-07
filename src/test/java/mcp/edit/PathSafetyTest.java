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
}
