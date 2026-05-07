# Cosmic MCP Slice 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 6 MCP tools that let an MCP client edit git-tracked Cosmic surfaces (JS scripts, `config.yaml`, drop-data SQL) and drive a basic git workflow (`diff`, `commit`, `revert`), gated behind `mcp.edit_enabled: false` (default).

**Architecture:** Reuse the in-process MCP module from Slice 1. New `mcp.edit` package holds shared edit infrastructure (`EditLock`, `PathSafety`, `DiffBuilder`, `FileEditEngine`, `GitRunner`). 3 edit tools delegate to `FileEditEngine` with surface-specific path validators and optional format checks. 3 git tools delegate to `GitRunner` (`ProcessBuilder` over the `git` CLI). Tools register conditionally in `Server.init()` when `mcp.edit_enabled: true`.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito (existing). One new runtime dep: `io.github.java-diff-utils:java-diff-utils:4.12`.

**Spec:** `docs/superpowers/specs/2026-05-07-cosmic-mcp-slice-2-design.md`.

---

## File Structure

```
src/main/java/mcp/
  edit/
    EditLock.java            Global ReentrantLock singleton, 2s tryLock
    PathSafety.java          Per-surface path validation; rejects ../, escapes, wrong extension
    DiffBuilder.java         Unified diff via java-diff-utils
    FileEditEngine.java      Shared find-replace / full-content / dry_run / atomic write
    GitRunner.java           ProcessBuilder wrapper (stdout/stderr/exit)
  tools/
    ScriptEditTool.java      14: cosmic.script.edit
    ConfigEditTool.java      15: cosmic.config.edit
    DropsEditSqlTool.java    16: cosmic.drops.edit_sql
    GitDiffTool.java         17: cosmic.git.diff
    GitCommitTool.java       18: cosmic.git.commit
    GitRevertTool.java       19: cosmic.git.revert

src/main/java/config/
  McpConfigYaml.java         + edit_enabled, repo_root

src/main/java/mcp/config/
  McpConfig.java             + editEnabled, repoRoot

config.yaml                  + edit_enabled: false, repo_root: "."
pom.xml                      + java-diff-utils
src/main/java/net/server/Server.java   conditional registration of 6 new tools
```

---

## Task 1: Add `java-diff-utils` and config keys

**Files:**
- Modify: `pom.xml`
- Modify: `config.yaml`
- Modify: `src/main/java/config/McpConfigYaml.java`
- Modify: `src/main/java/mcp/config/McpConfig.java`
- Modify: `src/test/java/mcp/config/McpConfigTest.java`

- [ ] **Step 1: Add property and dep to pom.xml**

In `pom.xml`, add to the version properties block (near other `*.version` lines):

```xml
<java-diff-utils.version>4.12</java-diff-utils.version>
```

In the `<!-- MCP -->` dependency block (before `<!-- Testing -->`), add:

```xml
<dependency>
    <groupId>io.github.java-diff-utils</groupId>
    <artifactId>java-diff-utils</artifactId>
    <version>${java-diff-utils.version}</version>
</dependency>
```

- [ ] **Step 2: Add new keys to `config.yaml`**

In `config.yaml`, append two keys to the existing `mcp:` block (after `request_log: true`):

```yaml
  edit_enabled: false
  repo_root: "."
```

- [ ] **Step 3: Add fields to `McpConfigYaml`**

Edit `src/main/java/config/McpConfigYaml.java`. After the existing `public boolean request_log;` field, add:

```java
    public boolean edit_enabled;
    public String repo_root;
```

- [ ] **Step 4: Update `McpConfig` record + factory**

Edit `src/main/java/mcp/config/McpConfig.java`.

a) Add `boolean editEnabled` and `String repoRoot` to the record component list (place them at the end so the existing constructor argument order in callers stays the same up to that point):

```java
public record McpConfig(
        boolean enabled,
        String bindAddr,
        int port,
        String authToken,
        String tlsCert,
        String tlsKey,
        boolean sqlEnabled,
        int sqlTimeoutSeconds,
        int sqlRowCap,
        List<String> sqlPiiDenylist,
        boolean requestLog,
        boolean editEnabled,
        String repoRoot
) {
```

b) Add a default constant near the existing defaults:

```java
    private static final String DEFAULT_REPO_ROOT = ".";
```

c) Update both `from(...)` returns:
- The disabled / null branch (`!y.enabled` and `y == null` cases) returns `editEnabled=false` and `repoRoot=""`. Update those `new McpConfig(...)` calls to add `, false, ""` at the end.
- The enabled branch adds `y.edit_enabled` and `y.repo_root == null || y.repo_root.isBlank() ? DEFAULT_REPO_ROOT : y.repo_root` at the end.

The full enabled-branch `new McpConfig(...)` call after the change:

```java
return new McpConfig(
        true,
        y.bind_addr == null ? DEFAULT_BIND_ADDR : y.bind_addr,
        y.port == 0 ? DEFAULT_PORT : y.port,
        y.auth_token,
        y.tls_cert == null ? "" : y.tls_cert,
        y.tls_key == null ? "" : y.tls_key,
        y.sql_enabled,
        y.sql_timeout_seconds == 0 ? DEFAULT_SQL_TIMEOUT_S : y.sql_timeout_seconds,
        y.sql_row_cap == 0 ? DEFAULT_SQL_ROW_CAP : y.sql_row_cap,
        y.sql_pii_denylist == null ? List.of() : List.copyOf(y.sql_pii_denylist),
        y.request_log,
        y.edit_enabled,
        y.repo_root == null || y.repo_root.isBlank() ? DEFAULT_REPO_ROOT : y.repo_root
);
```

- [ ] **Step 5: Add tests for the new fields**

Edit `src/test/java/mcp/config/McpConfigTest.java`. Add three new tests:

```java
@org.junit.jupiter.api.Test
void from_enabledEditDefault_repoRootDefaultsToDot() {
    McpConfigYaml y = baseEnabled();
    McpConfig c = McpConfig.from(y);
    assertFalse(c.editEnabled());
    assertEquals(".", c.repoRoot());
}

@org.junit.jupiter.api.Test
void from_enabledWithEditOn_returnsEditEnabled() {
    McpConfigYaml y = baseEnabled();
    y.edit_enabled = true;
    y.repo_root = "/srv/cosmic";
    McpConfig c = McpConfig.from(y);
    assertTrue(c.editEnabled());
    assertEquals("/srv/cosmic", c.repoRoot());
}

@org.junit.jupiter.api.Test
void from_enabledBlankRepoRoot_defaultsToDot() {
    McpConfigYaml y = baseEnabled();
    y.repo_root = "   ";
    McpConfig c = McpConfig.from(y);
    assertEquals(".", c.repoRoot());
}
```

Add these imports near the top of the file if missing:

```java
import static org.junit.jupiter.api.Assertions.assertTrue;
```

- [ ] **Step 6: Run tests**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test -Dtest=McpConfigTest -q
```
Expected: all `McpConfigTest` tests pass (existing 7 + 3 new = 10).

- [ ] **Step 7: Commit**

```bash
git add pom.xml config.yaml src/main/java/config/McpConfigYaml.java src/main/java/mcp/config/McpConfig.java src/test/java/mcp/config/McpConfigTest.java
git commit -m "Add edit_enabled / repo_root config + java-diff-utils dep #minor"
```

---

## Task 2: `EditLock`

**Files:**
- Create: `src/main/java/mcp/edit/EditLock.java`
- Create: `src/test/java/mcp/edit/EditLockTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.edit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditLockTest {

    @Test
    void tryAcquire_unlocked_returnsTrue() {
        EditLock lock = new EditLock();
        try {
            assertTrue(lock.tryAcquire());
        } finally {
            lock.release();
        }
    }

    @Test
    void tryAcquire_alreadyHeldByOtherThread_returnsFalse() throws Exception {
        EditLock lock = new EditLock();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            assertTrue(lock.tryAcquire());
            held.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            lock.release();
        });
        t.start();
        held.await();
        // Now the lock is held; tryAcquire from this thread should fail within 2s
        long start = System.nanoTime();
        boolean got = lock.tryAcquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(got);
        assertTrue(elapsedMs >= 1500, "expected ~2s wait, got " + elapsedMs + "ms");
        release.countDown();
        t.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    void release_withoutHolding_doesNotThrow() {
        EditLock lock = new EditLock();
        // Should be a no-op for a thread that never acquired
        lock.release();
    }
}
```

- [ ] **Step 2: Implement `EditLock`**

```java
package mcp.edit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class EditLock {

    private static final long TIMEOUT_SECONDS = 2;

    public static final EditLock INSTANCE = new EditLock();

    private final ReentrantLock lock = new ReentrantLock();

    public boolean tryAcquire() {
        try {
            return lock.tryLock(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=EditLockTest -q
```
Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/edit/EditLock.java src/test/java/mcp/edit/EditLockTest.java
git commit -m "Add EditLock for serializing MCP edits #minor"
```

---

## Task 3: `PathSafety`

**Files:**
- Create: `src/main/java/mcp/edit/PathSafety.java`
- Create: `src/test/java/mcp/edit/PathSafetyTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
    void resolveScript_validJsUnderScripts_succeeds() throws IOException {
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
    void resolveConfig_exact_succeeds() {
        Path resolved = PathSafety.resolveConfig(repoRoot, "config.yaml");
        assertEquals(repoRoot.resolve("config.yaml").normalize(), resolved);
    }

    @Test
    void resolveConfig_anythingElse_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveConfig(repoRoot, "config.local.yaml"));
    }

    @Test
    void resolveDrops_validKnownFile_succeeds() {
        Path resolved = PathSafety.resolveDrops(repoRoot, "src/main/resources/db/data/152-drop-data.sql");
        assertEquals(repoRoot.resolve("src/main/resources/db/data/152-drop-data.sql").normalize(), resolved);
    }

    @Test
    void resolveDrops_unknownDataFile_throws() {
        assertThrows(PathSafety.PathException.class,
                () -> PathSafety.resolveDrops(repoRoot, "src/main/resources/db/data/161-admin-data.sql"));
    }

    @Test
    void resolveAny_acceptsAnyAllowedSurface() {
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
```

- [ ] **Step 2: Implement `PathSafety`**

```java
package mcp.edit;

import java.nio.file.Path;
import java.util.Set;

public class PathSafety {

    private static final Set<String> ALLOWED_DROPS = Set.of(
            "src/main/resources/db/data/131-reactordrops-data.sql",
            "src/main/resources/db/data/151-global-drop-data.sql",
            "src/main/resources/db/data/152-drop-data.sql"
    );
    private static final String CONFIG_PATH = "config.yaml";
    private static final String SCRIPTS_PREFIX = "scripts/";

    private PathSafety() {}

    public static Path resolveScript(Path repoRoot, String input) throws PathException {
        if (input == null || input.contains("..")) throw deny(input);
        if (!input.startsWith(SCRIPTS_PREFIX)) throw deny(input);
        if (!input.endsWith(".js")) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveConfig(Path repoRoot, String input) throws PathException {
        if (!CONFIG_PATH.equals(input)) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveDrops(Path repoRoot, String input) throws PathException {
        if (input == null || input.contains("..")) throw deny(input);
        if (!ALLOWED_DROPS.contains(input)) throw deny(input);
        return resolveWithin(repoRoot, input);
    }

    public static Path resolveAny(Path repoRoot, String input) throws PathException {
        if (input == null) throw deny(input);
        if (CONFIG_PATH.equals(input)) return resolveConfig(repoRoot, input);
        if (input.startsWith(SCRIPTS_PREFIX)) return resolveScript(repoRoot, input);
        if (ALLOWED_DROPS.contains(input)) return resolveDrops(repoRoot, input);
        throw deny(input);
    }

    private static Path resolveWithin(Path repoRoot, String input) throws PathException {
        Path resolved = repoRoot.resolve(input).normalize();
        if (!resolved.startsWith(repoRoot.normalize())) throw deny(input);
        return resolved;
    }

    private static PathException deny(String input) {
        return new PathException("path not allowed: " + input);
    }

    public static class PathException extends Exception {
        public PathException(String msg) { super(msg); }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PathSafetyTest -q
```
Expected: 10 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/edit/PathSafety.java src/test/java/mcp/edit/PathSafetyTest.java
git commit -m "Add PathSafety with per-surface allow-lists #minor"
```

---

## Task 4: `DiffBuilder`

**Files:**
- Create: `src/main/java/mcp/edit/DiffBuilder.java`
- Create: `src/test/java/mcp/edit/DiffBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffBuilderTest {

    @Test
    void diff_identical_returnsEmpty() {
        String d = DiffBuilder.unified("foo.txt", "abc\n", "abc\n");
        assertEquals("", d);
    }

    @Test
    void diff_singleLineChange_returnsUnifiedDiff() {
        String d = DiffBuilder.unified("foo.txt", "hello\n", "world\n");
        assertTrue(d.contains("--- foo.txt"));
        assertTrue(d.contains("+++ foo.txt"));
        assertTrue(d.contains("-hello"));
        assertTrue(d.contains("+world"));
    }

    @Test
    void diff_creation_showsAllAsAdditions() {
        String d = DiffBuilder.unified("new.js", null, "var x = 1;\n");
        assertTrue(d.contains("--- /dev/null"));
        assertTrue(d.contains("+++ new.js"));
        assertTrue(d.contains("+var x = 1;"));
    }
}
```

- [ ] **Step 2: Implement `DiffBuilder`**

```java
package mcp.edit;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

public class DiffBuilder {

    private static final int CONTEXT_LINES = 3;

    private DiffBuilder() {}

    public static String unified(String path, String oldContent, String newContent) {
        if (oldContent != null && oldContent.equals(newContent)) return "";
        List<String> oldLines = oldContent == null ? List.of() : Arrays.asList(oldContent.split("\n", -1));
        List<String> newLines = newContent == null ? List.of() : Arrays.asList(newContent.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (patch.getDeltas().isEmpty()) return "";
        String fromPath = oldContent == null ? "/dev/null" : path;
        String toPath = newContent == null ? "/dev/null" : path;
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(fromPath, toPath, oldLines, patch, CONTEXT_LINES);
        return String.join("\n", unified) + "\n";
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=DiffBuilderTest -q
```
Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/edit/DiffBuilder.java src/test/java/mcp/edit/DiffBuilderTest.java
git commit -m "Add DiffBuilder using java-diff-utils #minor"
```

---

## Task 5: `FileEditEngine`

**Files:**
- Create: `src/main/java/mcp/edit/FileEditEngine.java`
- Create: `src/test/java/mcp/edit/FileEditEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Implement `FileEditEngine`**

```java
package mcp.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import mcp.tools.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileEditEngine {

    @FunctionalInterface
    public interface ContentValidator {
        void validate(String content) throws Tool.ToolException;
    }

    public ObjectNode apply(Path resolvedPath, String displayPath, JsonNode args, ContentValidator validator) throws Tool.ToolException {
        boolean hasFindReplace = args.has("old_string") || args.has("new_string");
        boolean hasContent = args.has("content");
        if (hasFindReplace && hasContent) {
            throw new Tool.ToolException(McpError.INVALID_PARAMS, "provide either find-replace fields or content, not both");
        }
        if (!hasFindReplace && !hasContent) {
            throw new Tool.ToolException(McpError.INVALID_PARAMS, "provide either {old_string,new_string} or content");
        }
        boolean dryRun = args.has("dry_run") && args.get("dry_run").asBoolean();

        if (!EditLock.INSTANCE.tryAcquire()) {
            throw new Tool.ToolException(McpError.SERVER_SHUTTING_DOWN, "edit_busy");
        }
        try {
            String oldContent = null;
            boolean exists = Files.exists(resolvedPath);
            if (exists) {
                try {
                    oldContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new Tool.ToolException(McpError.INTERNAL_ERROR, "read failed: " + e.getMessage());
                }
            }

            String newContent;
            if (hasContent) {
                newContent = args.get("content").asText();
            } else {
                if (!exists) {
                    throw new Tool.ToolException(McpError.INVALID_PARAMS, "no such file: " + displayPath);
                }
                String oldStr = args.get("old_string").asText();
                String newStr = args.get("new_string").asText();
                boolean replaceAll = args.has("replace_all") && args.get("replace_all").asBoolean();
                int idx = oldContent.indexOf(oldStr);
                if (idx < 0) {
                    throw new Tool.ToolException(McpError.INVALID_PARAMS, "old_string not found in " + displayPath);
                }
                if (!replaceAll) {
                    int second = oldContent.indexOf(oldStr, idx + 1);
                    if (second >= 0) {
                        int count = countOccurrences(oldContent, oldStr);
                        throw new Tool.ToolException(McpError.INVALID_PARAMS,
                                "old_string matches " + count + " times in " + displayPath + "; pass replace_all=true or expand context");
                    }
                    newContent = oldContent.substring(0, idx) + newStr + oldContent.substring(idx + oldStr.length());
                } else {
                    newContent = oldContent.replace(oldStr, newStr);
                }
            }

            if (validator != null) {
                validator.validate(newContent);
            }

            String diff = DiffBuilder.unified(displayPath, oldContent, newContent);

            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("path", displayPath);
            out.put("mode", dryRun ? "preview" : "applied");
            out.put("diff", diff);
            out.put("created", !exists && !dryRun);

            if (!dryRun) {
                try {
                    Files.createDirectories(resolvedPath.getParent());
                    Path tmp = Files.createTempFile(resolvedPath.getParent(), ".mcp-edit-", ".tmp");
                    Files.writeString(tmp, newContent, StandardCharsets.UTF_8);
                    Files.move(tmp, resolvedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    throw new Tool.ToolException(McpError.INTERNAL_ERROR, "write failed: " + e.getMessage());
                }
            }
            return out;
        } finally {
            EditLock.INSTANCE.release();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) return count;
            count++;
            from = i + needle.length();
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=FileEditEngineTest -q
```
Expected: 10 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/edit/FileEditEngine.java src/test/java/mcp/edit/FileEditEngineTest.java
git commit -m "Add FileEditEngine for shared edit logic #minor"
```

---

## Task 6: `ScriptEditTool`

**Files:**
- Create: `src/main/java/mcp/tools/ScriptEditTool.java`
- Create: `src/test/java/mcp/tools/ScriptEditToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Implement `ScriptEditTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.FileEditEngine;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.nio.file.Path;

public class ScriptEditTool implements Tool {

    private final Path repoRoot;
    private final FileEditEngine engine;

    public ScriptEditTool(Path repoRoot) {
        this(repoRoot, new FileEditEngine());
    }

    ScriptEditTool(Path repoRoot, FileEditEngine engine) {
        this.repoRoot = repoRoot;
        this.engine = engine;
    }

    @Override
    public String name() { return "cosmic.script.edit"; }

    @Override
    public String description() { return "Edit or create a Cosmic JS script under scripts/. Find-replace OR full content."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Path under scripts/, ending in .js.");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean");
        props.putObject("content").put("type", "string");
        props.putObject("dry_run").put("type", "boolean");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        Path resolved;
        try {
            resolved = PathSafety.resolveScript(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        return engine.apply(resolved, input, args, null);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=ScriptEditToolTest -q
```
Expected: 6 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/ScriptEditTool.java src/test/java/mcp/tools/ScriptEditToolTest.java
git commit -m "Add cosmic.script.edit MCP tool #minor"
```

---

## Task 7: `ConfigEditTool`

**Files:**
- Create: `src/main/java/mcp/tools/ConfigEditTool.java`
- Create: `src/test/java/mcp/tools/ConfigEditToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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

class ConfigEditToolTest {

    @TempDir
    Path repoRoot;

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.config.edit", new ConfigEditTool(repoRoot).name());
    }

    @Test
    void call_findReplace_succeeds() throws Exception {
        Files.writeString(repoRoot.resolve("config.yaml"), "server:\n  port: 8484\n");
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.yaml");
        args.put("old_string", "8484");
        args.put("new_string", "8485");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("server:\n  port: 8485\n", Files.readString(repoRoot.resolve("config.yaml")));
    }

    @Test
    void call_invalidYaml_throwsAndDoesNotWrite() throws IOException {
        Files.writeString(repoRoot.resolve("config.yaml"), "server:\n  port: 8484\n");
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.yaml");
        args.put("content", "not valid: [yaml");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("invalid YAML"));
        assertEquals("server:\n  port: 8484\n", Files.readString(repoRoot.resolve("config.yaml")), "file unchanged");
    }

    @Test
    void call_pathNotConfig_throws() {
        ConfigEditTool tool = new ConfigEditTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "config.local.yaml");
        args.put("content", "x: 1\n");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 2: Implement `ConfigEditTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import mcp.edit.FileEditEngine;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.nio.file.Path;

public class ConfigEditTool implements Tool {

    private static final YAMLMapper YAML = new YAMLMapper();

    private final Path repoRoot;
    private final FileEditEngine engine;

    public ConfigEditTool(Path repoRoot) {
        this(repoRoot, new FileEditEngine());
    }

    ConfigEditTool(Path repoRoot, FileEditEngine engine) {
        this.repoRoot = repoRoot;
        this.engine = engine;
    }

    @Override
    public String name() { return "cosmic.config.edit"; }

    @Override
    public String description() { return "Edit config.yaml. Find-replace OR full content. Post-edit YAML parse check."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Must equal config.yaml.");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean");
        props.putObject("content").put("type", "string");
        props.putObject("dry_run").put("type", "boolean");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        Path resolved;
        try {
            resolved = PathSafety.resolveConfig(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        FileEditEngine.ContentValidator yamlCheck = content -> {
            try {
                YAML.readTree(content);
            } catch (JsonProcessingException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid YAML: " + e.getOriginalMessage());
            }
        };
        return engine.apply(resolved, input, args, yamlCheck);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=ConfigEditToolTest -q
```
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/ConfigEditTool.java src/test/java/mcp/tools/ConfigEditToolTest.java
git commit -m "Add cosmic.config.edit MCP tool with YAML validation #minor"
```

---

## Task 8: `DropsEditSqlTool`

**Files:**
- Create: `src/main/java/mcp/tools/DropsEditSqlTool.java`
- Create: `src/test/java/mcp/tools/DropsEditSqlToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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

class DropsEditSqlToolTest {

    @TempDir
    Path repoRoot;

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.drops.edit_sql", new DropsEditSqlTool(repoRoot).name());
    }

    @Test
    void call_findReplaceOnAllowed_succeeds() throws Exception {
        Path f = repoRoot.resolve("src/main/resources/db/data/152-drop-data.sql");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "INSERT INTO drop_data VALUES (100100, 1002357, 1, 1, 0, 50000);\n");
        DropsEditSqlTool tool = new DropsEditSqlTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/resources/db/data/152-drop-data.sql");
        args.put("old_string", "50000");
        args.put("new_string", "60000");
        JsonNode out = tool.call(args);
        assertEquals("applied", out.get("mode").asText());
        assertEquals("INSERT INTO drop_data VALUES (100100, 1002357, 1, 1, 0, 60000);\n",
                Files.readString(f));
    }

    @Test
    void call_unknownDataFile_throws() {
        DropsEditSqlTool tool = new DropsEditSqlTool(repoRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "src/main/resources/db/data/161-admin-data.sql");
        args.put("content", "x");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 2: Implement `DropsEditSqlTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.FileEditEngine;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.nio.file.Path;

public class DropsEditSqlTool implements Tool {

    private final Path repoRoot;
    private final FileEditEngine engine;

    public DropsEditSqlTool(Path repoRoot) {
        this(repoRoot, new FileEditEngine());
    }

    DropsEditSqlTool(Path repoRoot, FileEditEngine engine) {
        this.repoRoot = repoRoot;
        this.engine = engine;
    }

    @Override
    public String name() { return "cosmic.drops.edit_sql"; }

    @Override
    public String description() { return "Edit a drop-data SQL file (drop_data, drop_data_global, reactordrops)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        props.putObject("replace_all").put("type", "boolean");
        props.putObject("content").put("type", "string");
        props.putObject("dry_run").put("type", "boolean");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        Path resolved;
        try {
            resolved = PathSafety.resolveDrops(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        return engine.apply(resolved, input, args, null);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=DropsEditSqlToolTest -q
```
Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/DropsEditSqlTool.java src/test/java/mcp/tools/DropsEditSqlToolTest.java
git commit -m "Add cosmic.drops.edit_sql MCP tool #minor"
```

---

## Task 9: `GitRunner`

**Files:**
- Create: `src/main/java/mcp/edit/GitRunner.java`
- Create: `src/test/java/mcp/edit/GitRunnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.edit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
```

- [ ] **Step 2: Implement `GitRunner`**

```java
package mcp.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitRunner {

    private final Path workingDir;

    public GitRunner(Path workingDir) {
        this.workingDir = workingDir;
    }

    public Result run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir.toFile()).redirectErrorStream(false);
        Process p = pb.start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return new Result(-1, "", "git timed out");
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.exitValue(), stdout, stderr);
    }

    public record Result(int exitCode, String stdout, String stderr) {}
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=GitRunnerTest -q
```
Expected: 2 tests PASS (or skip if `git` not on PATH).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/edit/GitRunner.java src/test/java/mcp/edit/GitRunnerTest.java
git commit -m "Add GitRunner ProcessBuilder wrapper #minor"
```

---

## Task 10: `GitDiffTool`

**Files:**
- Create: `src/main/java/mcp/tools/GitDiffTool.java`
- Create: `src/test/java/mcp/tools/GitDiffToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Implement `GitDiffTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitDiffTool implements Tool {

    private static final List<String> DEFAULT_PATHS = List.of(
            "scripts",
            "config.yaml",
            "src/main/resources/db/data/131-reactordrops-data.sql",
            "src/main/resources/db/data/151-global-drop-data.sql",
            "src/main/resources/db/data/152-drop-data.sql"
    );

    private final Path repoRoot;
    private final GitRunner runner;

    public GitDiffTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitDiffTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.diff"; }

    @Override
    public String description() { return "Show uncommitted diff for an allowed path, or all editable surfaces."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        List<String> argList = new ArrayList<>();
        argList.add("diff");
        argList.add("--no-color");
        argList.add("--");
        if (args.has("path") && args.get("path").isTextual()) {
            String input = args.get("path").asText();
            try {
                PathSafety.resolveAny(repoRoot, input);
            } catch (PathSafety.PathException e) {
                throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
            }
            argList.add(input);
        } else {
            argList.addAll(DEFAULT_PATHS);
        }
        try {
            GitRunner.Result r = runner.run(argList.toArray(new String[0]));
            if (r.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git diff failed: " + r.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("diff", r.stdout());
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git diff failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=GitDiffToolTest -q
```
Expected: 4 tests PASS (or skip if no git).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/GitDiffTool.java src/test/java/mcp/tools/GitDiffToolTest.java
git commit -m "Add cosmic.git.diff MCP tool #minor"
```

---

## Task 11: `GitCommitTool`

**Files:**
- Create: `src/main/java/mcp/tools/GitCommitTool.java`
- Create: `src/test/java/mcp/tools/GitCommitToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Implement `GitCommitTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitCommitTool implements Tool {

    private final Path repoRoot;
    private final GitRunner runner;

    public GitCommitTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitCommitTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.commit"; }

    @Override
    public String description() { return "Stage allowed paths and commit with a message. No push."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode paths = props.putObject("paths");
        paths.put("type", "array");
        paths.putObject("items").put("type", "string");
        props.putObject("message").put("type", "string");
        ArrayNode required = root.putArray("required");
        required.add("paths");
        required.add("message");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("paths") || !args.get("paths").isArray() || args.get("paths").isEmpty()) {
            throw new ToolException(McpError.INVALID_PARAMS, "paths must be a non-empty array");
        }
        if (!args.has("message") || !args.get("message").isTextual() || args.get("message").asText().isBlank()) {
            throw new ToolException(McpError.INVALID_PARAMS, "message must be non-empty");
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode p : args.get("paths")) {
            String s = p.asText();
            try {
                PathSafety.resolveAny(repoRoot, s);
            } catch (PathSafety.PathException e) {
                throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
            }
            paths.add(s);
        }
        String message = args.get("message").asText();
        try {
            List<String> addCmd = new ArrayList<>();
            addCmd.add("add");
            addCmd.add("--");
            addCmd.addAll(paths);
            GitRunner.Result addResult = runner.run(addCmd.toArray(new String[0]));
            if (addResult.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git add failed: " + addResult.stderr().trim());
            }
            GitRunner.Result commitResult = runner.run("commit", "-m", message);
            if (commitResult.exitCode() != 0) {
                List<String> resetCmd = new ArrayList<>();
                resetCmd.add("reset");
                resetCmd.add("HEAD");
                resetCmd.add("--");
                resetCmd.addAll(paths);
                runner.run(resetCmd.toArray(new String[0]));
                throw new ToolException(McpError.INTERNAL_ERROR, "git commit failed: " + commitResult.stderr().trim());
            }
            GitRunner.Result shaResult = runner.run("rev-parse", "HEAD");
            if (shaResult.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git rev-parse failed: " + shaResult.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("sha", shaResult.stdout().trim());
            ArrayNode committed = out.putArray("files_committed");
            for (String p : paths) committed.add(p);
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=GitCommitToolTest -q
```
Expected: 4 tests PASS (or skip if no git).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/GitCommitTool.java src/test/java/mcp/tools/GitCommitToolTest.java
git commit -m "Add cosmic.git.commit MCP tool #minor"
```

---

## Task 12: `GitRevertTool`

**Files:**
- Create: `src/main/java/mcp/tools/GitRevertTool.java`
- Create: `src/test/java/mcp/tools/GitRevertToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Implement `GitRevertTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.edit.GitRunner;
import mcp.edit.PathSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Path;

public class GitRevertTool implements Tool {

    private final Path repoRoot;
    private final GitRunner runner;

    public GitRevertTool(Path repoRoot) {
        this(repoRoot, new GitRunner(repoRoot));
    }

    GitRevertTool(Path repoRoot, GitRunner runner) {
        this.repoRoot = repoRoot;
        this.runner = runner;
    }

    @Override
    public String name() { return "cosmic.git.revert"; }

    @Override
    public String description() { return "Discard uncommitted changes for an allowed path (working tree only)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String input = args.get("path").asText();
        try {
            PathSafety.resolveAny(repoRoot, input);
        } catch (PathSafety.PathException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        try {
            GitRunner.Result r = runner.run("checkout", "--", input);
            if (r.exitCode() != 0) {
                throw new ToolException(McpError.INTERNAL_ERROR, "git checkout failed: " + r.stderr().trim());
            }
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("path", input);
            out.put("reverted", true);
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolException(McpError.INTERNAL_ERROR, "git checkout failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=GitRevertToolTest -q
```
Expected: 2 tests PASS (or skip if no git).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/GitRevertTool.java src/test/java/mcp/tools/GitRevertToolTest.java
git commit -m "Add cosmic.git.revert MCP tool #minor"
```

---

## Task 13: Wire 6 tools into `Server.init()` + README

**Files:**
- Modify: `src/main/java/net/server/Server.java`
- Modify: `README.md`

- [ ] **Step 1: Conditionally register the 6 new tools**

Edit `src/main/java/net/server/Server.java`. In the existing MCP boot block (where `mcpTools` is built), after the SQL tool registration but BEFORE `new ToolRegistry(mcpTools)`, add:

```java
                if (mcpConfig.editEnabled()) {
                    java.nio.file.Path repoRoot = java.nio.file.Path.of(mcpConfig.repoRoot()).toAbsolutePath().normalize();
                    if (!java.nio.file.Files.isDirectory(repoRoot)) {
                        log.warn("MCP edit_enabled=true but repo_root is not a directory: {} (skipping edit tools)", repoRoot);
                    } else {
                        mcpTools.add(new mcp.tools.ScriptEditTool(repoRoot));
                        mcpTools.add(new mcp.tools.ConfigEditTool(repoRoot));
                        mcpTools.add(new mcp.tools.DropsEditSqlTool(repoRoot));
                        mcpTools.add(new mcp.tools.GitDiffTool(repoRoot));
                        mcpTools.add(new mcp.tools.GitCommitTool(repoRoot));
                        mcpTools.add(new mcp.tools.GitRevertTool(repoRoot));
                    }
                }
```

- [ ] **Step 2: Verify compile + full test suite**

```bash
./mvnw compile -q
./mvnw test -q
```
Expected: BUILD SUCCESS. All tests pass (existing 1963 + new ~50).

- [ ] **Step 3: Add README section**

Edit `README.md`. After the existing "MCP server" section (added in Slice 1), append a new subsection:

```markdown
#### MCP edit tools (Slice 2)

In addition to read-only research, Cosmic's MCP server can edit git-tracked surfaces — JS scripts, `config.yaml`, and drop-data SQL files — and drive a basic git workflow (`diff`, `commit`, `revert`). These are **disabled by default**.

To enable, set `mcp.edit_enabled: true` in `config.yaml` and (optionally) `mcp.repo_root` to the absolute path of your Cosmic checkout if the JVM's working directory is not the repo root.

Tools added: `cosmic.script.edit`, `cosmic.config.edit`, `cosmic.drops.edit_sql`, `cosmic.git.diff`, `cosmic.git.commit`, `cosmic.git.revert`. Each edit tool accepts either find-replace (`old_string` / `new_string` / optional `replace_all`) or a full `content` string, plus an optional `dry_run: true` to preview the diff without writing.

Live game-state and live-DB writes are out of scope for Slice 2 — see `docs/superpowers/specs/2026-05-07-cosmic-mcp-slice-2-design.md`.
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/server/Server.java README.md
git commit -m "Wire MCP edit tools into Server lifecycle + README #minor"
```

---

## Summary of new tools after Task 13

| # | Tool | Task |
|---|---|---|
| 14 | cosmic.script.edit | 6 |
| 15 | cosmic.config.edit | 7 |
| 16 | cosmic.drops.edit_sql | 8 |
| 17 | cosmic.git.diff | 10 |
| 18 | cosmic.git.commit | 11 |
| 19 | cosmic.git.revert | 12 |

All 6 spec'd tools accounted for. Tasks 1-5 build the shared infrastructure (config, EditLock, PathSafety, DiffBuilder, FileEditEngine). Task 9 builds GitRunner. Task 13 wires everything into Cosmic's lifecycle.

## Manual verification checklist (post-implementation)

- [ ] Set `mcp.enabled: true`, `mcp.edit_enabled: true`, `mcp.auth_token: <16+ chars>`. Start Cosmic.
- [ ] `cosmic.script.edit` with find-replace on a real NPC script — confirm the file changes and Cosmic hot-reloads on next NPC interaction.
- [ ] `cosmic.script.edit` with `content` for a brand-new file under `scripts/quest/` — confirm `created: true`.
- [ ] `cosmic.script.edit` with `dry_run: true` — confirm response includes diff and the file is unchanged.
- [ ] `cosmic.config.edit` with valid YAML — confirm write.
- [ ] `cosmic.config.edit` with `content: "not: valid: [yaml"` — confirm `-32602 invalid YAML` and the file is unchanged.
- [ ] `cosmic.drops.edit_sql` on `152-drop-data.sql` — confirm write.
- [ ] `cosmic.drops.edit_sql` on `161-admin-data.sql` — confirm `-32602 path not allowed`.
- [ ] `cosmic.git.diff` with no path — confirm output contains all uncommitted changes within the editable surfaces.
- [ ] `cosmic.git.diff` with `path: "src/main/java/foo"` — confirm `-32602 path not allowed`.
- [ ] `cosmic.git.commit` with valid paths and message — confirm new SHA in response.
- [ ] `cosmic.git.commit` with `paths: ["src/main/java/foo"]` — confirm rejection.
- [ ] `cosmic.git.revert` on a still-uncommitted edit — confirm file reverts.
- [ ] `cosmic.script.edit` with `path: "../etc/passwd"` — confirm `-32602 path not allowed`.
- [ ] After `mcp.edit_enabled: false` restart — confirm `tools/list` returns 13 tools (Slice 1 only), not 19.
