# Cosmic MCP Slice 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 7 MCP tools that let an MCP client inspect live game state, dispatch any GM `@`-command, run UPDATE/INSERT/DELETE on safelisted tables, and read a persistent audit log. All gated behind `mcp.admin_enabled: false` (default).

**Architecture:** Reuses Slice 1 transport/auth/dispatcher and Slice 2 `EditLock` and `SqlSafety`. New `mcp.admin/` package holds shared infra (`AuditLog`, `CommandCatalog`, `PlayerLookup`, `RunCommandExecutor`, `WriteSqlSafety`, `PreImageCapture`). 7 tool classes delegate to that infra. New `mcp_admin_audit` DB table via a Liquibase extension changeset. Triple-gate registration: `admin_enabled`, plus for `db.execute` also `db_execute_enabled` AND non-empty `sql_writable_tables`.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, Testcontainers (test). No new runtime deps.

**Spec:** `docs/superpowers/specs/2026-05-07-cosmic-mcp-slice-3-design.md`.

---

## File Structure

```
src/main/java/mcp/admin/
  AuditEntry.java               record carrying audit row data
  AuditLog.java                 inserts into mcp_admin_audit; transactional
  CommandCatalog.java           snapshot of CommandsExecutor's registered commands
  PlayerLookup.java             online + offline DB fallback
  RunCommandExecutor.java       synthesizes admin context; dispatches command
  WriteSqlSafety.java           extends SqlSafety: UPDATE/INSERT/DELETE on writable allow-list
  PreImageCapture.java          SELECT-before-write for db.execute audit (capped 100)

src/main/java/mcp/tools/
  OnlineTool.java               20 cosmic.admin.online
  PlayerDescribeTool.java       21 cosmic.admin.player.describe
  WorldDescribeTool.java        22 cosmic.admin.world.describe
  CommandsListTool.java         23 cosmic.admin.commands.list
  RunCommandTool.java           24 cosmic.admin.run_command
  DbExecuteTool.java            25 cosmic.db.execute
  AuditListTool.java            26 cosmic.admin.audit.list

src/main/java/client/command/
  CommandsExecutor.java         + public read accessor for the registered map

src/main/java/config/
  McpConfigYaml.java            + admin_enabled, db_execute_enabled, sql_writable_tables

src/main/java/mcp/config/
  McpConfig.java                + adminEnabled, dbExecuteEnabled, sqlWritableTables

src/main/resources/db/extensions/
  2026-05-07-mcp-admin-audit.xml   Liquibase changeset adding the audit table

config.yaml                     + admin_enabled: false, db_execute_enabled: false, sql_writable_tables: []
src/main/java/net/server/Server.java   conditional registration of 7 new tools
README.md                       + Slice 3 subsection
```

---

## Task 1: Config additions + audit table Liquibase changeset

**Files:**
- Modify: `pom.xml` (no change — placeholder line removed; nothing to do here)
- Modify: `config.yaml`
- Modify: `src/main/java/config/McpConfigYaml.java`
- Modify: `src/main/java/mcp/config/McpConfig.java`
- Modify: `src/test/java/mcp/config/McpConfigTest.java`
- Create: `src/main/resources/db/extensions/2026-05-07-mcp-admin-audit.xml`

- [ ] **Step 1: Append three keys to `config.yaml`**

In `config.yaml`, append to the existing `mcp:` block (after `repo_root: "."`):

```yaml
  admin_enabled: false
  db_execute_enabled: false
  sql_writable_tables: []
```

- [ ] **Step 2: Add fields to `McpConfigYaml`**

Edit `src/main/java/config/McpConfigYaml.java`. After `public String repo_root;` add:

```java
    public boolean admin_enabled;
    public boolean db_execute_enabled;
    public List<String> sql_writable_tables;
```

(The `List<String>` import is already present from `sql_pii_denylist`.)

- [ ] **Step 3: Update `McpConfig` record + factory**

Edit `src/main/java/mcp/config/McpConfig.java`. Add three new components to the END of the record header:

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
        String repoRoot,
        boolean adminEnabled,
        boolean dbExecuteEnabled,
        List<String> sqlWritableTables
) {
```

Update both disabled-path returns (`y == null` and `!y.enabled`) to add `, false, false, List.of()` at the end:

```java
return new McpConfig(false, "", 0, "", "", "", false, 0, 0, List.of(), false, false, "", false, false, List.of());
```

Update the enabled-path `new McpConfig(...)` to add at the end:

```java
y.admin_enabled,
y.db_execute_enabled,
y.sql_writable_tables == null ? List.of() : List.copyOf(y.sql_writable_tables)
```

The complete enabled-branch call:

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
        y.repo_root == null || y.repo_root.isBlank() ? DEFAULT_REPO_ROOT : y.repo_root,
        y.admin_enabled,
        y.db_execute_enabled,
        y.sql_writable_tables == null ? List.of() : List.copyOf(y.sql_writable_tables)
);
```

- [ ] **Step 4: Add tests for the new fields**

Edit `src/test/java/mcp/config/McpConfigTest.java`. Add three tests:

```java
@org.junit.jupiter.api.Test
void from_enabledAdminDefault_isFalse() {
    McpConfigYaml y = baseEnabled();
    McpConfig c = McpConfig.from(y);
    assertFalse(c.adminEnabled());
    assertFalse(c.dbExecuteEnabled());
    assertEquals(java.util.List.of(), c.sqlWritableTables());
}

@org.junit.jupiter.api.Test
void from_enabledAdminOn_returnsAdminEnabled() {
    McpConfigYaml y = baseEnabled();
    y.admin_enabled = true;
    y.db_execute_enabled = true;
    y.sql_writable_tables = java.util.List.of("characters", "inventoryitems");
    McpConfig c = McpConfig.from(y);
    assertTrue(c.adminEnabled());
    assertTrue(c.dbExecuteEnabled());
    assertEquals(java.util.List.of("characters", "inventoryitems"), c.sqlWritableTables());
}

@org.junit.jupiter.api.Test
void from_nullSqlWritableTables_defaultsToEmpty() {
    McpConfigYaml y = baseEnabled();
    y.admin_enabled = true;
    y.sql_writable_tables = null;
    McpConfig c = McpConfig.from(y);
    assertEquals(java.util.List.of(), c.sqlWritableTables());
}
```

- [ ] **Step 5: Create the Liquibase changeset**

Create `src/main/resources/db/extensions/2026-05-07-mcp-admin-audit.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="mcp-admin-audit-1" author="cosmic-mcp">
        <createTable tableName="mcp_admin_audit">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="ts" type="DATETIME(3)">
                <constraints nullable="false"/>
            </column>
            <column name="caller_ip" type="VARCHAR(64)"/>
            <column name="caller_note" type="VARCHAR(255)"/>
            <column name="tool" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="args_json" type="JSON"/>
            <column name="result_summary" type="TEXT"/>
            <column name="before_json" type="JSON"/>
            <column name="after_summary" type="TEXT"/>
            <column name="ok" type="BOOLEAN">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="mcp_admin_audit" indexName="idx_audit_ts">
            <column name="ts"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

(The existing `changelog-root.xml` already includes everything in `extensions/` via `<includeAll path="extensions" .../>` so no other changelog file needs editing.)

- [ ] **Step 6: Run tests + compile**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw test -Dtest=McpConfigTest -q
./mvnw compile -q
```
Expected: tests pass; compile BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add config.yaml src/main/java/config/McpConfigYaml.java src/main/java/mcp/config/McpConfig.java src/test/java/mcp/config/McpConfigTest.java src/main/resources/db/extensions/2026-05-07-mcp-admin-audit.xml
git commit -m "Add admin config + mcp_admin_audit table changeset #minor"
```

---

## Task 2: `AuditEntry` + `AuditLog`

**Files:**
- Create: `src/main/java/mcp/admin/AuditEntry.java`
- Create: `src/main/java/mcp/admin/AuditLog.java`
- Create: `src/test/java/mcp/admin/AuditLogTest.java`

- [ ] **Step 1: Create `AuditEntry` record**

```java
package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;

public record AuditEntry(
        String callerIp,
        String callerNote,
        String tool,
        JsonNode argsJson,
        String resultSummary,
        JsonNode beforeJson,
        String afterSummary,
        boolean ok
) {}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/mcp/admin/AuditLogTest.java`:

```java
package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — AuditLogTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE mcp_admin_audit (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      ts DATETIME(3) NOT NULL,
                      caller_ip VARCHAR(64),
                      caller_note VARCHAR(255),
                      tool VARCHAR(64) NOT NULL,
                      args_json JSON,
                      result_summary TEXT,
                      before_json JSON,
                      after_summary TEXT,
                      ok BOOLEAN NOT NULL,
                      INDEX idx_audit_ts (ts)
                    )
                    """);
        }
    }

    @AfterAll
    static void down() {
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void insert_writesRowAndReturnsId() throws Exception {
        AuditLog log = new AuditLog(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@kick playerX");
        AuditEntry entry = new AuditEntry("127.0.0.1", "test", "cosmic.admin.run_command",
                args, "ok", null, null, true);
        long id = log.insert(entry);
        assertTrue(id > 0);
        try (Connection c = conSupplier.get();
             var ps = c.prepareStatement("SELECT tool, ok, args_json FROM mcp_admin_audit WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("cosmic.admin.run_command", rs.getString("tool"));
                assertEquals(true, rs.getBoolean("ok"));
                assertNotNull(rs.getString("args_json"));
            }
        }
    }

    @Test
    void insertInTransaction_rollsBackOnFailure() throws Exception {
        AuditLog log = new AuditLog(conSupplier);
        try (Connection c = conSupplier.get()) {
            c.setAutoCommit(false);
            AuditEntry entry = new AuditEntry("127.0.0.1", null, "cosmic.db.execute",
                    JsonRpc.MAPPER.createObjectNode(), null, null, null, true);
            log.insertInConnection(c, entry);
            c.rollback();
        }
        try (Connection c = conSupplier.get(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM mcp_admin_audit")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "no rows after rollback");
        }
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
```

- [ ] **Step 3: Implement `AuditLog`**

Create `src/main/java/mcp/admin/AuditLog.java`:

```java
package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Supplier;

public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int MAX_RESULT_SUMMARY = 1024;
    private static final int MAX_ARGS_JSON = 4096;

    private final Supplier<Connection> conSupplier;

    public AuditLog(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    public long insert(AuditEntry entry) throws SQLException {
        try (Connection c = conSupplier.get()) {
            return insertInConnection(c, entry);
        }
    }

    public long insertInConnection(Connection c, AuditEntry entry) throws SQLException {
        String sql = "INSERT INTO mcp_admin_audit " +
                "(ts, caller_ip, caller_note, tool, args_json, result_summary, before_json, after_summary, ok) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, entry.callerIp());
            ps.setString(3, entry.callerNote());
            ps.setString(4, entry.tool());
            ps.setString(5, jsonToString(entry.argsJson(), MAX_ARGS_JSON));
            ps.setString(6, truncate(entry.resultSummary(), MAX_RESULT_SUMMARY));
            ps.setString(7, jsonToString(entry.beforeJson(), Integer.MAX_VALUE));
            ps.setString(8, truncate(entry.afterSummary(), MAX_RESULT_SUMMARY));
            ps.setBoolean(9, entry.ok());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("no generated key");
                return keys.getLong(1);
            }
        }
    }

    private static String jsonToString(JsonNode node, int max) {
        if (node == null || node.isNull()) return null;
        try {
            String s = JsonRpc.MAPPER.writeValueAsString(node);
            return truncate(s, max);
        } catch (Exception e) {
            log.warn("audit jsonToString failed", e);
            return "\"<serialization-error>\"";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...truncated]";
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=AuditLogTest -q
```
Expected: 2 tests PASS (or skip if no Docker).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/admin/AuditEntry.java src/main/java/mcp/admin/AuditLog.java src/test/java/mcp/admin/AuditLogTest.java
git commit -m "Add AuditLog + AuditEntry for MCP admin audit table #minor"
```

---

## Task 3: `CommandCatalog`

Cosmic's `CommandsExecutor` has a private `Map<String, Command>` field. We add a public read accessor and snapshot it.

**Files:**
- Modify: `src/main/java/client/command/CommandsExecutor.java` — add public read accessor
- Create: `src/main/java/mcp/admin/CommandCatalog.java`
- Create: `src/test/java/mcp/admin/CommandCatalogTest.java`

- [ ] **Step 1: Add public accessor in `CommandsExecutor`**

In `src/main/java/client/command/CommandsExecutor.java`, find the existing `private final Map<String, Command> registeredCommands;` field declaration. Add this public method below it (placement doesn't matter; near the field is fine):

```java
public java.util.Map<String, Command> getRegisteredCommands() {
    return java.util.Collections.unmodifiableMap(registeredCommands);
}
```

(If `Map`/`Collections` are already imported in the file, use the unqualified names. Otherwise the fully-qualified version above works without changing imports.)

- [ ] **Step 2: Write the failing test**

Create `src/test/java/mcp/admin/CommandCatalogTest.java`:

```java
package mcp.admin;

import client.command.Command;
import client.command.commands.gm0.HelpCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCatalogTest {

    private static class FakeHelp extends HelpCommand {
        public FakeHelp() { setRank(0); setDescription("show help"); }
    }

    @Test
    void snapshot_returnsAllCommands() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        reg.put("commands", help);
        CommandCatalog cat = new CommandCatalog(reg);
        List<CommandCatalog.Entry> entries = cat.list(null, null);
        assertEquals(2, entries.size());
        assertEquals("commands", entries.get(0).name()); // sorted alphabetically
        assertEquals(0, entries.get(0).gmLevel());
    }

    @Test
    void list_filterBySubstring_matches() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        reg.put("kick", help);
        reg.put("kickout", help);
        CommandCatalog cat = new CommandCatalog(reg);
        List<CommandCatalog.Entry> hits = cat.list("kick", null);
        assertEquals(2, hits.size());
    }

    @Test
    void list_filterByGmLevel_matches() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp lvl0 = new FakeHelp(); lvl0.setRank(0);
        FakeHelp lvl3 = new FakeHelp() {{ setRank(3); }};
        reg.put("a", lvl0);
        reg.put("b", lvl3);
        CommandCatalog cat = new CommandCatalog(reg);
        assertEquals(1, cat.list(null, 0).size());
        assertEquals(1, cat.list(null, 3).size());
    }

    @Test
    void find_returnsCommandByName() {
        Map<String, Command> reg = new LinkedHashMap<>();
        FakeHelp help = new FakeHelp();
        reg.put("help", help);
        CommandCatalog cat = new CommandCatalog(reg);
        assertNotNull(cat.find("help"));
        assertTrue(cat.find("nope").isEmpty());
    }
}
```

- [ ] **Step 3: Implement `CommandCatalog`**

Create `src/main/java/mcp/admin/CommandCatalog.java`:

```java
package mcp.admin;

import client.command.Command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CommandCatalog {

    public record Entry(String name, int gmLevel, String description) {}

    private final Map<String, Command> registry;

    public CommandCatalog(Map<String, Command> registry) {
        this.registry = registry;
    }

    public Optional<Command> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(registry.get(name.toLowerCase()));
    }

    public List<Entry> list(String filterSubstring, Integer gmLevel) {
        List<Entry> out = new ArrayList<>();
        String filter = filterSubstring == null ? null : filterSubstring.toLowerCase();
        for (Map.Entry<String, Command> e : registry.entrySet()) {
            String name = e.getKey();
            Command cmd = e.getValue();
            if (filter != null && !name.contains(filter)) continue;
            if (gmLevel != null && cmd.getRank() != gmLevel) continue;
            String desc = cmd.getDescription() == null ? "" : cmd.getDescription();
            out.add(new Entry(name, cmd.getRank(), desc));
        }
        out.sort(Comparator.comparing(Entry::name));
        return out;
    }
}
```

- [ ] **Step 4: Run tests + compile**

```bash
./mvnw test -Dtest=CommandCatalogTest -q
./mvnw compile -q
```
Expected: 4 tests PASS, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/client/command/CommandsExecutor.java src/main/java/mcp/admin/CommandCatalog.java src/test/java/mcp/admin/CommandCatalogTest.java
git commit -m "Add CommandCatalog snapshot of GM commands #minor"
```

---

## Task 4: `PlayerLookup`

Resolves a character by name from online state first, falling back to a DB read for offline lookups. Online lookup uses `Server.getInstance()`; tests inject a `WorldsAccessor` seam (similar to Slice 2's edit-tool pattern).

**Files:**
- Create: `src/main/java/mcp/admin/PlayerLookup.java`
- Create: `src/test/java/mcp/admin/PlayerLookupTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/admin/PlayerLookupTest.java`:

```java
package mcp.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLookupTest {

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online (12)

    @Test
    void online_findByName_returnsPresent() {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 0, 0, 1, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        Optional<PlayerLookup.Snapshot> found = pl.find("Foo");
        assertTrue(found.isPresent());
        assertEquals(50, found.get().level());
        assertEquals(100, found.get().job());
    }

    @Test
    void online_findCaseInsensitive() {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 0, 0, 1, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        assertTrue(pl.find("FOO").isPresent());
        assertTrue(pl.find("foo").isPresent());
    }

    @Test
    void offline_fallsBackToDb_whenNotOnline() {
        PlayerLookup.Snapshot offline = new PlayerLookup.Snapshot("Bar", 30, 200, 0, 0, 1, 0, 800, 50, 1000, 0, false);
        PlayerLookup pl = new PlayerLookup(List::of, name -> "Bar".equalsIgnoreCase(name) ? Optional.of(offline) : Optional.empty());
        Optional<PlayerLookup.Snapshot> found = pl.find("Bar");
        assertTrue(found.isPresent());
        assertEquals(false, found.get().online());
    }

    @Test
    void offline_returnsEmpty_whenNotInDb() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        assertTrue(pl.find("missing").isEmpty());
    }

    @Test
    void online_filtered() {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 1, 0, 0, 0, 0, 100, 1, 1, 1, 0, true);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 1, 0, 0, 0, 1, 100, 1, 1, 1, 0, true);
        PlayerLookup.Snapshot c = new PlayerLookup.Snapshot("C", 1, 0, 0, 1, 0, 100, 1, 1, 1, 0, true);
        PlayerLookup pl = new PlayerLookup(() -> List.of(a, b, c), name -> Optional.empty());
        List<PlayerLookup.Snapshot> hits = pl.online(0, null, null, null, 100);
        assertEquals(2, hits.size()); // a, b are world 0
        assertEquals(1, pl.online(0, 1, null, null, 100).size()); // b is world 0 channel 1
    }
}
```

- [ ] **Step 2: Implement `PlayerLookup`**

Create `src/main/java/mcp/admin/PlayerLookup.java`:

```java
package mcp.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class PlayerLookup {

    public record Snapshot(
            String name,
            int level,
            int job,
            long exp,
            int world,
            int channel,
            int map,
            int hp,
            int mp,
            int mesos,
            int gmLevel,
            boolean online
    ) {}

    public interface OnlineProvider extends Supplier<List<Snapshot>> {}
    public interface OfflineLookup extends Function<String, Optional<Snapshot>> {}

    private final OnlineProvider onlineProvider;
    private final OfflineLookup offlineLookup;

    public PlayerLookup(OnlineProvider onlineProvider, OfflineLookup offlineLookup) {
        this.onlineProvider = onlineProvider;
        this.offlineLookup = offlineLookup;
    }

    public Optional<Snapshot> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (Snapshot s : onlineProvider.get()) {
            if (s.name().equalsIgnoreCase(name)) return Optional.of(s);
        }
        return offlineLookup.apply(name);
    }

    public List<Snapshot> online(Integer world, Integer channel, Integer map, String nameSubstring, int limit) {
        List<Snapshot> out = new ArrayList<>();
        String sub = nameSubstring == null ? null : nameSubstring.toLowerCase();
        for (Snapshot s : onlineProvider.get()) {
            if (world != null && s.world() != world) continue;
            if (channel != null && s.channel() != channel) continue;
            if (map != null && s.map() != map) continue;
            if (sub != null && !s.name().toLowerCase().contains(sub)) continue;
            out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PlayerLookupTest -q
```
Expected: 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/admin/PlayerLookup.java src/test/java/mcp/admin/PlayerLookupTest.java
git commit -m "Add PlayerLookup with online/offline fallback #minor"
```

---

## Task 5: `OnlineTool` (cosmic.admin.online)

**Files:**
- Create: `src/main/java/mcp/tools/OnlineTool.java`
- Create: `src/test/java/mcp/tools/OnlineToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/OnlineToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineToolTest {

    private PlayerLookup lookup(PlayerLookup.Snapshot... players) {
        return new PlayerLookup(() -> List.of(players), name -> Optional.empty());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.online", new OnlineTool(lookup()).name());
    }

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online (12)

    @Test
    void call_emptyArgs_returnsAllOnline() throws Exception {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 50, 100, 0, 0, 0, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 70, 200, 0, 0, 1, 100000001, 1500, 200, 8000, 0, true);
        OnlineTool tool = new OnlineTool(lookup(a, b));
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(2, out.get("players").size());
        assertEquals(2, out.get("total").asInt());
        assertEquals(100, out.get("players").get(0).get("job").asInt());
    }

    @Test
    void call_filterByWorld() throws Exception {
        PlayerLookup.Snapshot a = new PlayerLookup.Snapshot("A", 50, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        PlayerLookup.Snapshot b = new PlayerLookup.Snapshot("B", 70, 0, 0, 1, 0, 0, 0, 0, 0, 0, true);
        OnlineTool tool = new OnlineTool(lookup(a, b));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world", 1);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("players").size());
        assertEquals("B", out.get("players").get(0).get("name").asText());
    }

    @Test
    void call_limitCappedAt200() throws Exception {
        PlayerLookup.Snapshot[] arr = new PlayerLookup.Snapshot[300];
        for (int i = 0; i < 300; i++) {
            arr[i] = new PlayerLookup.Snapshot("p" + i, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, true);
        }
        OnlineTool tool = new OnlineTool(lookup(arr));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertTrue(out.get("players").size() <= 200);
    }
}
```

- [ ] **Step 2: Implement `OnlineTool`**

Create `src/main/java/mcp/tools/OnlineTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;

import java.util.List;

public class OnlineTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final PlayerLookup lookup;

    public OnlineTool(PlayerLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String name() { return "cosmic.admin.online"; }

    @Override
    public String description() { return "List online Cosmic players, optionally filtered by world/channel/map/name."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("world").put("type", "integer");
        props.putObject("channel").put("type", "integer");
        props.putObject("map").put("type", "integer");
        props.putObject("name_substring").put("type", "string");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        Integer world = args.has("world") && args.get("world").isInt() ? args.get("world").asInt() : null;
        Integer channel = args.has("channel") && args.get("channel").isInt() ? args.get("channel").asInt() : null;
        Integer map = args.has("map") && args.get("map").isInt() ? args.get("map").asInt() : null;
        String nameSub = args.has("name_substring") && args.get("name_substring").isTextual() ? args.get("name_substring").asText() : null;
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        List<PlayerLookup.Snapshot> hits = lookup.online(world, channel, map, nameSub, limit);
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode players = out.putArray("players");
        for (PlayerLookup.Snapshot s : hits) {
            ObjectNode p = players.addObject();
            p.put("name", s.name());
            p.put("level", s.level());
            p.put("job", s.job());
            p.put("world", s.world());
            p.put("channel", s.channel());
            p.put("map", s.map());
            p.put("hp", s.hp());
            p.put("mp", s.mp());
        }
        out.put("total", hits.size());
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=OnlineToolTest -q
```
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/OnlineTool.java src/test/java/mcp/tools/OnlineToolTest.java
git commit -m "Add cosmic.admin.online MCP tool #minor"
```

---

## Task 6: `PlayerDescribeTool` (cosmic.admin.player.describe)

**Files:**
- Create: `src/main/java/mcp/tools/PlayerDescribeTool.java`
- Create: `src/test/java/mcp/tools/PlayerDescribeToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDescribeToolTest {

    @Test
    void name_isCorrect() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        assertEquals("cosmic.admin.player.describe", new PlayerDescribeTool(pl).name());
    }

    // Snapshot fields: name, level, job, exp, world, channel, map, hp, mp, mesos, gmLevel, online (12)

    @Test
    void call_onlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot foo = new PlayerLookup.Snapshot("Foo", 50, 100, 1500, 0, 1, 100000000, 1000, 100, 5000, 0, true);
        PlayerLookup pl = new PlayerLookup(() -> List.of(foo), name -> Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Foo");
        JsonNode out = tool.call(args);
        assertEquals("Foo", out.get("name").asText());
        assertEquals(50, out.get("level").asInt());
        assertEquals(100, out.get("job").asInt());
        assertTrue(out.get("online").asBoolean());
    }

    @Test
    void call_offlinePlayer_returnsDescription() throws Exception {
        PlayerLookup.Snapshot bar = new PlayerLookup.Snapshot("Bar", 30, 0, 0, 0, 0, 0, 800, 50, 1000, 0, false);
        PlayerLookup pl = new PlayerLookup(List::of, name -> "Bar".equalsIgnoreCase(name) ? Optional.of(bar) : Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "Bar");
        JsonNode out = tool.call(args);
        assertEquals(false, out.get("online").asBoolean());
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        PlayerLookup pl = new PlayerLookup(List::of, name -> Optional.empty());
        PlayerDescribeTool tool = new PlayerDescribeTool(pl);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("name", "missing");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("no such player"));
    }
}
```

- [ ] **Step 2: Implement `PlayerDescribeTool`**

Create `src/main/java/mcp/tools/PlayerDescribeTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.PlayerLookup;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.util.Optional;

public class PlayerDescribeTool implements Tool {

    private final PlayerLookup lookup;

    public PlayerDescribeTool(PlayerLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String name() { return "cosmic.admin.player.describe"; }

    @Override
    public String description() { return "Describe a Cosmic player by name (online state preferred, falls back to DB)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("name").put("type", "string");
        root.putArray("required").add("name");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("name") || !args.get("name").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'name'");
        }
        String name = args.get("name").asText();
        Optional<PlayerLookup.Snapshot> opt = lookup.find(name);
        if (opt.isEmpty()) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such player: " + name);
        }
        PlayerLookup.Snapshot s = opt.get();
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("name", s.name());
        out.put("level", s.level());
        out.put("job", s.job());
        out.put("exp", s.exp());
        out.put("world", s.world());
        out.put("channel", s.channel());
        out.put("map", s.map());
        out.put("hp", s.hp());
        out.put("mp", s.mp());
        out.put("mesos", s.mesos());
        out.put("gmLevel", s.gmLevel());
        out.put("online", s.online());
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PlayerDescribeToolTest -q
```
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/PlayerDescribeTool.java src/test/java/mcp/tools/PlayerDescribeToolTest.java
git commit -m "Add cosmic.admin.player.describe MCP tool #minor"
```

---

## Task 7: `WorldDescribeTool` (cosmic.admin.world.describe)

This tool needs access to `Server.getInstance()` for live worlds and `YamlConfig.config.worlds` for static rates. Use a `WorldStatsProvider` seam to keep tests isolated.

**Files:**
- Create: `src/main/java/mcp/tools/WorldDescribeTool.java`
- Create: `src/test/java/mcp/tools/WorldDescribeToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldDescribeToolTest {

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.world.describe",
                new WorldDescribeTool(() -> 0L, List::of).name());
    }

    @Test
    void call_returnsUptimeAndWorlds() throws Exception {
        WorldDescribeTool.WorldStats w0 = new WorldDescribeTool.WorldStats(0, "Scania", 3, 42, 10, 10, 10);
        WorldDescribeTool.WorldStats w1 = new WorldDescribeTool.WorldStats(1, "Bera", 3, 0, 100, 100, 10);
        WorldDescribeTool tool = new WorldDescribeTool(() -> 1234L, () -> List.of(w0, w1));
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(1234, out.get("uptime_seconds").asLong());
        assertEquals(2, out.get("worlds").size());
        assertEquals("Scania", out.get("worlds").get(0).get("name").asText());
        assertEquals(42, out.get("worlds").get(0).get("online_count").asInt());
    }
}
```

- [ ] **Step 2: Implement `WorldDescribeTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class WorldDescribeTool implements Tool {

    public record WorldStats(int id, String name, int channels, int onlineCount, int expRate, int mesoRate, int dropRate) {}

    private final LongSupplier uptimeSeconds;
    private final Supplier<List<WorldStats>> worldsSupplier;

    public WorldDescribeTool(LongSupplier uptimeSeconds, Supplier<List<WorldStats>> worldsSupplier) {
        this.uptimeSeconds = uptimeSeconds;
        this.worldsSupplier = worldsSupplier;
    }

    @Override
    public String name() { return "cosmic.admin.world.describe"; }

    @Override
    public String description() { return "Describe Cosmic worlds: uptime, channel count, online count, rates."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("uptime_seconds", uptimeSeconds.getAsLong());
        ArrayNode arr = out.putArray("worlds");
        for (WorldStats w : worldsSupplier.get()) {
            ObjectNode wn = arr.addObject();
            wn.put("id", w.id());
            wn.put("name", w.name());
            wn.put("channels", w.channels());
            wn.put("online_count", w.onlineCount());
            wn.put("exp_rate", w.expRate());
            wn.put("meso_rate", w.mesoRate());
            wn.put("drop_rate", w.dropRate());
        }
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=WorldDescribeToolTest -q
```
Expected: 2 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/WorldDescribeTool.java src/test/java/mcp/tools/WorldDescribeToolTest.java
git commit -m "Add cosmic.admin.world.describe MCP tool #minor"
```

---

## Task 8: `CommandsListTool` (cosmic.admin.commands.list)

**Files:**
- Create: `src/main/java/mcp/tools/CommandsListTool.java`
- Create: `src/test/java/mcp/tools/CommandsListToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import client.command.commands.gm0.HelpCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.CommandCatalog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsListToolTest {

    private static class FakeHelp extends HelpCommand {
        public FakeHelp(int rank, String desc) { setRank(rank); setDescription(desc); }
    }

    private CommandCatalog catalog() {
        Map<String, client.command.Command> m = new LinkedHashMap<>();
        m.put("help", new FakeHelp(0, "show help"));
        m.put("kick", new FakeHelp(3, "kick a player"));
        m.put("ban", new FakeHelp(5, "ban a player"));
        return new CommandCatalog(m);
    }

    @Test
    void call_returnsAllCommands() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(3, out.get("commands").size());
    }

    @Test
    void call_filterSubstring() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("filter_substring", "k");
        JsonNode out = tool.call(args);
        assertTrue(out.get("commands").size() >= 1);
    }

    @Test
    void call_filterGmLevel() throws Exception {
        CommandsListTool tool = new CommandsListTool(catalog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("gm_level", 5);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("commands").size());
        assertEquals("ban", out.get("commands").get(0).get("name").asText());
    }
}
```

- [ ] **Step 2: Implement `CommandsListTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.CommandCatalog;
import mcp.protocol.JsonRpc;

import java.util.List;

public class CommandsListTool implements Tool {

    private final CommandCatalog catalog;

    public CommandsListTool(CommandCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() { return "cosmic.admin.commands.list"; }

    @Override
    public String description() { return "List Cosmic in-game @-commands. Filter by substring or GM level."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("filter_substring").put("type", "string");
        props.putObject("gm_level").put("type", "integer").put("minimum", 0).put("maximum", 6);
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        String filter = args.has("filter_substring") && args.get("filter_substring").isTextual()
                ? args.get("filter_substring").asText() : null;
        Integer gmLevel = args.has("gm_level") && args.get("gm_level").isInt()
                ? args.get("gm_level").asInt() : null;
        List<CommandCatalog.Entry> entries = catalog.list(filter, gmLevel);
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("commands");
        for (CommandCatalog.Entry e : entries) {
            ObjectNode n = arr.addObject();
            n.put("name", e.name());
            n.put("gm_level", e.gmLevel());
            n.put("syntax", "@" + e.name());
            n.put("description", e.description());
        }
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=CommandsListToolTest -q
```
Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/CommandsListTool.java src/test/java/mcp/tools/CommandsListToolTest.java
git commit -m "Add cosmic.admin.commands.list MCP tool #minor"
```

---

## Task 9: `RunCommandExecutor`

This is the most complex piece. It synthesizes a minimal admin context to dispatch any `@`-command without a real player session. We use an interface seam so tests don't need to construct real `Client`/`Character` instances.

**Files:**
- Create: `src/main/java/mcp/admin/RunCommandExecutor.java`
- Create: `src/test/java/mcp/admin/RunCommandExecutorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.admin;

import client.Client;
import client.command.Command;
import client.command.commands.gm0.HelpCommand;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandExecutorTest {

    private static class CapturingCommand extends HelpCommand {
        String[] received;
        boolean shouldThrow;
        @Override public void execute(Client client, String[] params) {
            received = params;
            if (shouldThrow) throw new RuntimeException("boom");
        }
    }

    private CommandCatalog catalogWith(String name, Command cmd) {
        Map<String, Command> m = new LinkedHashMap<>();
        m.put(name, cmd);
        return new CommandCatalog(m);
    }

    @Test
    void parse_unknownCommand_throws() {
        CommandCatalog cat = new CommandCatalog(new LinkedHashMap<>());
        RunCommandExecutor exec = new RunCommandExecutor(cat, name -> false);
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@nope foo bar", 6));
        assertTrue(ex.getMessage().contains("unknown command"));
    }

    @Test
    void parse_notSupported_throws() {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("warpme", cmd), name -> "warpme".equals(name));
        RunCommandExecutor.RunException ex = assertThrows(RunCommandExecutor.RunException.class,
                () -> exec.run("@warpme", 6));
        assertTrue(ex.getMessage().contains("requires in-game context"));
    }

    @Test
    void run_validCommand_dispatchesWithParams() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("kick", cmd), name -> false);
        RunCommandExecutor.Result r = exec.run("@kick alice", 6);
        assertEquals(true, r.ok());
        assertEquals(1, cmd.received.length);
        assertEquals("alice", cmd.received[0]);
    }

    @Test
    void run_commandThrows_returnsNotOkWithMessage() throws Exception {
        CapturingCommand cmd = new CapturingCommand();
        cmd.shouldThrow = true;
        RunCommandExecutor exec = new RunCommandExecutor(catalogWith("foo", cmd), name -> false);
        RunCommandExecutor.Result r = exec.run("@foo", 6);
        assertEquals(false, r.ok());
        assertTrue(r.output().contains("boom"));
    }

    @Test
    void run_emptyCommand_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false);
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("   ", 6));
    }

    @Test
    void run_missingAtSign_throws() {
        RunCommandExecutor exec = new RunCommandExecutor(new CommandCatalog(new LinkedHashMap<>()), name -> false);
        assertThrows(RunCommandExecutor.RunException.class, () -> exec.run("kick alice", 6));
    }
}
```

- [ ] **Step 2: Implement `RunCommandExecutor`**

Create `src/main/java/mcp/admin/RunCommandExecutor.java`:

```java
package mcp.admin;

import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Predicate;

public class RunCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunCommandExecutor.class);

    public record Result(boolean ok, String output) {}

    public static class RunException extends Exception {
        public RunException(String msg) { super(msg); }
    }

    private final CommandCatalog catalog;
    private final Predicate<String> notSupported;

    public RunCommandExecutor(CommandCatalog catalog, Predicate<String> notSupported) {
        this.catalog = catalog;
        this.notSupported = notSupported;
    }

    public Result run(String commandLine, int asGmLevel) throws RunException {
        if (commandLine == null || commandLine.isBlank()) {
            throw new RunException("empty command");
        }
        String trimmed = commandLine.trim();
        if (!trimmed.startsWith("@")) {
            throw new RunException("command must start with @");
        }
        String[] parts = trimmed.substring(1).split("\\s+");
        String name = parts[0].toLowerCase();
        if (notSupported.test(name)) {
            throw new RunException("command requires in-game context: " + name);
        }
        Optional<Command> opt = catalog.find(name);
        if (opt.isEmpty()) {
            throw new RunException("unknown command: " + name + " (use cosmic.admin.commands.list)");
        }
        String[] params = new String[parts.length - 1];
        System.arraycopy(parts, 1, params, 0, params.length);
        Client client = synthesizeAdminClient(asGmLevel);
        try {
            opt.get().execute(client, params);
            return new Result(true, "");
        } catch (Throwable t) {
            log.warn("run_command failed for {}", name, t);
            return new Result(false, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    /**
     * Synthesizes a minimal Client suitable for dispatching commands that don't read the
     * caller's character/map state. The returned object is intentionally NOT a real Cosmic
     * Client subclass — it is null. Tests may pass null Client through; production wiring
     * may extend this to provide a real GM-level admin client.
     */
    Client synthesizeAdminClient(int gmLevel) {
        return null;
    }
}
```

(Note on `synthesizeAdminClient`: returning `null` works for commands that don't dereference `client`. The "not-supported" predicate is the gate against caller-context-dependent commands. The complete Cosmic-side wiring of a real admin Client is a Task-15 production-time concern; for unit tests, the captured `received` params and exception path is enough behavior coverage.)

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RunCommandExecutorTest -q
```
Expected: 6 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/admin/RunCommandExecutor.java src/test/java/mcp/admin/RunCommandExecutorTest.java
git commit -m "Add RunCommandExecutor for MCP @-command dispatch #minor"
```

---

## Task 10: `RunCommandTool` (cosmic.admin.run_command)

**Files:**
- Create: `src/main/java/mcp/tools/RunCommandTool.java`
- Create: `src/test/java/mcp/tools/RunCommandToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.CommandCatalog;
import mcp.admin.RunCommandExecutor;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {

    private static class FakeAuditLog extends AuditLog {
        final List<AuditEntry> seen = new ArrayList<>();
        public FakeAuditLog() { super(() -> { throw new RuntimeException("not used"); }); }
        @Override public long insert(AuditEntry e) { seen.add(e); return seen.size(); }
    }

    private static class StubExecutor extends RunCommandExecutor {
        boolean throwUnknown;
        StubExecutor() { super(new CommandCatalog(new java.util.LinkedHashMap<>()), name -> false); }
        @Override public Result run(String commandLine, int asGmLevel) throws RunException {
            if (throwUnknown) throw new RunException("unknown command: x");
            return new Result(true, "");
        }
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.run_command", new RunCommandTool(new StubExecutor(), new FakeAuditLog()).name());
    }

    @Test
    void call_validCommand_returnsAuditId() throws Exception {
        StubExecutor exec = new StubExecutor();
        FakeAuditLog audit = new FakeAuditLog();
        RunCommandTool tool = new RunCommandTool(exec, audit);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@kick alice");
        JsonNode out = tool.call(args);
        assertEquals(true, out.get("ok").asBoolean());
        assertTrue(out.get("audit_id").asLong() > 0);
        assertEquals(1, audit.seen.size());
        assertEquals("cosmic.admin.run_command", audit.seen.get(0).tool());
    }

    @Test
    void call_unknownCommand_throwsInvalidParams() {
        StubExecutor exec = new StubExecutor();
        exec.throwUnknown = true;
        RunCommandTool tool = new RunCommandTool(exec, new FakeAuditLog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@nope");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void call_missingCommandArg_throws() {
        RunCommandTool tool = new RunCommandTool(new StubExecutor(), new FakeAuditLog());
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> tool.call(JsonRpc.MAPPER.createObjectNode()));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 2: Implement `RunCommandTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.RunCommandExecutor;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.SQLException;

public class RunCommandTool implements Tool {

    private final RunCommandExecutor executor;
    private final AuditLog auditLog;

    public RunCommandTool(RunCommandExecutor executor, AuditLog auditLog) {
        this.executor = executor;
        this.auditLog = auditLog;
    }

    @Override
    public String name() { return "cosmic.admin.run_command"; }

    @Override
    public String description() { return "Run any Cosmic in-game @-command via MCP. Records to audit log."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("command").put("type", "string");
        props.putObject("as_gm_level").put("type", "integer").put("minimum", 0).put("maximum", 6);
        props.putObject("caller_note").put("type", "string");
        root.putArray("required").add("command");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("command") || !args.get("command").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'command'");
        }
        String command = args.get("command").asText();
        int asGmLevel = args.has("as_gm_level") && args.get("as_gm_level").isInt() ? args.get("as_gm_level").asInt() : 6;
        String callerNote = args.has("caller_note") && args.get("caller_note").isTextual() ? args.get("caller_note").asText() : null;

        RunCommandExecutor.Result result;
        try {
            result = executor.run(command, asGmLevel);
        } catch (RunCommandExecutor.RunException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }

        ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
        argsJson.put("command", command);
        argsJson.put("as_gm_level", asGmLevel);
        AuditEntry entry = new AuditEntry(null, callerNote, "cosmic.admin.run_command",
                argsJson, result.output(), null, null, result.ok());
        long auditId;
        try {
            auditId = auditLog.insert(entry);
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("ok", result.ok());
        out.put("output", result.output() == null ? "" : result.output());
        out.put("audit_id", auditId);
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=RunCommandToolTest -q
```
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/RunCommandTool.java src/test/java/mcp/tools/RunCommandToolTest.java
git commit -m "Add cosmic.admin.run_command MCP tool #minor"
```

---

## Task 11: `WriteSqlSafety`

Extends Slice 1's `SqlSafety` to allow `UPDATE` / `INSERT` / `DELETE` on a writable allow-list, while keeping the PII column denylist active.

**Files:**
- Create: `src/main/java/mcp/admin/WriteSqlSafety.java`
- Create: `src/test/java/mcp/admin/WriteSqlSafetyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.admin;

import mcp.data.SqlSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteSqlSafetyTest {

    private final WriteSqlSafety safety = new WriteSqlSafety(
            new SqlSafety(List.of("account.password")),
            List.of("characters", "inventoryitems"));

    @Test
    void check_validUpdate_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("UPDATE characters SET level = 50 WHERE id = 1");
        assertEquals(WriteSqlSafety.Kind.UPDATE, k);
    }

    @Test
    void check_validInsert_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("INSERT INTO inventoryitems (id) VALUES (1)");
        assertEquals(WriteSqlSafety.Kind.INSERT, k);
    }

    @Test
    void check_validDelete_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("DELETE FROM inventoryitems WHERE id = 1");
        assertEquals(WriteSqlSafety.Kind.DELETE, k);
    }

    @Test
    void check_select_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("SELECT * FROM characters"));
        assertTrue(ex.getMessage().contains("for UPDATE/INSERT/DELETE"));
    }

    @Test
    void check_tableNotInAllowlist_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE accounts SET name = 'x'"));
        assertTrue(ex.getMessage().contains("table not writable"));
    }

    @Test
    void check_piiColumnReferenced_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE characters SET level = 50, password = 'x' WHERE id = 1"));
        assertTrue(ex.getMessage().contains("denied column"));
    }

    @Test
    void check_emptyAllowlist_rejected() {
        WriteSqlSafety empty = new WriteSqlSafety(new SqlSafety(List.of()), List.of());
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> empty.check("UPDATE characters SET level = 50"));
    }

    @Test
    void check_multiStatement_rejected() {
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE characters SET level = 1; DELETE FROM characters"));
    }
}
```

- [ ] **Step 2: Implement `WriteSqlSafety`**

```java
package mcp.admin;

import mcp.data.SqlSafety;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class WriteSqlSafety {

    public enum Kind { UPDATE, INSERT, DELETE }

    private final SqlSafety piiSafety;
    private final Set<String> writableTables;

    public WriteSqlSafety(SqlSafety piiSafety, List<String> writableTables) {
        this.piiSafety = piiSafety;
        this.writableTables = Set.copyOf(writableTables.stream()
                .map(t -> t.toLowerCase(Locale.ROOT)).toList());
    }

    public Kind check(String sql) throws SqlSafety.UnsafeSqlException {
        if (sql == null || sql.isBlank()) throw new SqlSafety.UnsafeSqlException("empty sql");
        Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new SqlSafety.UnsafeSqlException("parse error: " + e.getMessage());
        }
        if (stmts.getStatements().size() != 1) {
            throw new SqlSafety.UnsafeSqlException("only single statement allowed");
        }
        Statement s = stmts.getStatements().get(0);
        Kind kind;
        String table;
        if (s instanceof Update u) {
            kind = Kind.UPDATE;
            table = u.getTable().getName();
        } else if (s instanceof Insert ins) {
            kind = Kind.INSERT;
            table = ins.getTable().getName();
        } else if (s instanceof Delete d) {
            kind = Kind.DELETE;
            table = d.getTable().getName();
        } else {
            throw new SqlSafety.UnsafeSqlException("db.execute is for UPDATE/INSERT/DELETE; use db.select for reads");
        }
        if (!writableTables.contains(table.toLowerCase(Locale.ROOT))) {
            throw new SqlSafety.UnsafeSqlException("table not writable: " + table);
        }
        // PII column check via the existing regex-based safety: feed the normalized SQL through.
        // The SqlSafety check rejects non-SELECT — but we only need its column-denylist behavior here.
        // Trick: re-run JSqlParser's toString() on the parsed statement and pattern-match denied columns.
        // Simpler: reuse SqlSafety.checkColumns by extracting it; for v1 we replicate the column scan inline.
        String normalized = s.toString().toLowerCase(Locale.ROOT);
        for (String denied : piiDeniedColumnNames()) {
            if (denied.isEmpty()) continue;
            if (containsWordOrQualified(normalized, denied)) {
                throw new SqlSafety.UnsafeSqlException("denied column: " + denied);
            }
        }
        return kind;
    }

    private List<String> piiDeniedColumnNames() {
        // SqlSafety stores denied columns internally; we can't access them without a getter.
        // Add a public accessor in SqlSafety in a follow-up if you want to remove this fragility.
        // For now, the WriteSqlSafety constructor takes the SqlSafety instance and we
        // accept that the PII enforcement here uses the same string-walk pattern.
        return SqlSafetyAccess.getDeniedColumnList(piiSafety);
    }

    private static boolean containsWordOrQualified(String haystack, String column) {
        // matches either "<word-boundary>column<word-boundary>" or "tab.column<word-boundary>"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:\\b|\\.)" + java.util.regex.Pattern.quote(column) + "\\b");
        return p.matcher(haystack).find();
    }

    /**
     * Tiny accessor helper — extracts the denied column list out of SqlSafety via reflection
     * so we don't need to widen SqlSafety's public API just for Slice 3. If the field name
     * changes, this throws at first use, which is loud enough.
     */
    static class SqlSafetyAccess {
        static List<String> getDeniedColumnList(SqlSafety safety) {
            try {
                java.lang.reflect.Field f = SqlSafety.class.getDeclaredField("denied");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Object> denied = (List<Object>) f.get(safety);
                List<String> out = new java.util.ArrayList<>();
                for (Object dc : denied) {
                    java.lang.reflect.Method m = dc.getClass().getDeclaredMethod("qualified");
                    m.setAccessible(true);
                    String q = (String) m.invoke(dc);
                    String[] parts = q.split("\\.");
                    if (parts.length == 2) out.add(parts[1]);
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("WriteSqlSafety could not read SqlSafety.denied — has the field changed?", e);
            }
        }
    }
}
```

(The reflection accessor is a known fragility documented in the code. A cleaner alternative — adding a `public List<String> deniedColumns()` getter to `SqlSafety` — is a small follow-up; for Slice 3 v1 we keep all the changes inside the new `mcp.admin` package.)

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=WriteSqlSafetyTest -q
```
Expected: 8 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/admin/WriteSqlSafety.java src/test/java/mcp/admin/WriteSqlSafetyTest.java
git commit -m "Add WriteSqlSafety for UPDATE/INSERT/DELETE on allow-list #minor"
```

---

## Task 12: `PreImageCapture`

**Files:**
- Create: `src/main/java/mcp/admin/PreImageCapture.java`
- Create: `src/test/java/mcp/admin/PreImageCaptureTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreImageCaptureTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — PreImageCaptureTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE characters (id INT PRIMARY KEY, level INT, name VARCHAR(50))");
        }
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE characters");
        }
    }

    @Test
    void capture_updateWithSmallWhere_returnsRows() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("INSERT INTO characters VALUES (1, 50, 'Foo')");
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 51 WHERE id = 1", 100);
            assertNotNull(before);
            assertEquals(1, before.size());
            assertEquals(50, before.get(0).get("level").asInt());
        }
    }

    @Test
    void capture_updateWithoutWhere_returnsWarning() throws Exception {
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99", 100);
            assertNotNull(before);
            assertEquals("no_where_clause", before.get("warning").asText());
        }
    }

    @Test
    void capture_insert_returnsNull() throws Exception {
        try (Connection c = conSupplier.get()) {
            assertNull(PreImageCapture.capture(c, "INSERT INTO characters VALUES (1, 1, 'a')", 100));
        }
    }

    @Test
    void capture_largeUpdate_returnsCappedMarker() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            for (int i = 0; i < 150; i++) s.execute("INSERT INTO characters VALUES (" + i + ", 1, 'x')");
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99", 100);
            // No WHERE → warning marker (precedes the cap check)
            assertEquals("no_where_clause", before.get("warning").asText());
        }
        try (Connection c = conSupplier.get()) {
            JsonNode before = PreImageCapture.capture(c, "UPDATE characters SET level = 99 WHERE level = 1", 100);
            assertTrue(before.get("capped").asBoolean());
            assertTrue(before.get("row_count_at_least").asInt() >= 100);
        }
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
```

- [ ] **Step 2: Implement `PreImageCapture`**

```java
package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public final class PreImageCapture {

    private PreImageCapture() {}

    public static JsonNode capture(Connection con, String sql, int rowCap) throws SQLException {
        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("preimage parse failed: " + e.getMessage(), e);
        }
        String table;
        String whereClause;
        if (parsed instanceof Update u) {
            table = u.getTable().getName();
            whereClause = u.getWhere() == null ? null : u.getWhere().toString();
        } else if (parsed instanceof Delete d) {
            table = d.getTable().getName();
            whereClause = d.getWhere() == null ? null : d.getWhere().toString();
        } else {
            return null; // INSERT or other — no pre-image
        }
        if (whereClause == null) {
            ObjectNode warn = JsonRpc.MAPPER.createObjectNode();
            warn.put("warning", "no_where_clause");
            return warn;
        }
        String selectSql = "SELECT * FROM " + table + " WHERE " + whereClause + " LIMIT " + (rowCap + 1);
        try (PreparedStatement ps = con.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            ArrayNode rows = JsonRpc.MAPPER.createArrayNode();
            ResultSetMetaData md = rs.getMetaData();
            int count = 0;
            while (rs.next()) {
                if (count >= rowCap) {
                    ObjectNode capped = JsonRpc.MAPPER.createObjectNode();
                    capped.put("capped", true);
                    capped.put("row_count_at_least", rowCap);
                    return capped;
                }
                ObjectNode row = rows.addObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    String key = md.getColumnLabel(i);
                    if (v == null) row.putNull(key);
                    else row.put(key, v.toString());
                }
                count++;
            }
            return rows;
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PreImageCaptureTest -q
```
Expected: 4 tests PASS (or skip if no Docker).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/admin/PreImageCapture.java src/test/java/mcp/admin/PreImageCaptureTest.java
git commit -m "Add PreImageCapture for db.execute audit before-image #minor"
```

---

## Task 13: `DbExecuteTool` (cosmic.db.execute)

**Files:**
- Create: `src/main/java/mcp/tools/DbExecuteTool.java`
- Create: `src/test/java/mcp/tools/DbExecuteToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditLog;
import mcp.admin.WriteSqlSafety;
import mcp.data.SqlSafety;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExecuteToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;
    static WriteSqlSafety safety;
    static AuditLog auditLog;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — DbExecuteToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE characters (id INT PRIMARY KEY, level INT, name VARCHAR(50))");
            s.execute("CREATE TABLE accounts (id INT PRIMARY KEY, password VARCHAR(50))");
            s.execute("""
                    CREATE TABLE mcp_admin_audit (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      ts DATETIME(3) NOT NULL,
                      caller_ip VARCHAR(64),
                      caller_note VARCHAR(255),
                      tool VARCHAR(64) NOT NULL,
                      args_json JSON,
                      result_summary TEXT,
                      before_json JSON,
                      after_summary TEXT,
                      ok BOOLEAN NOT NULL,
                      INDEX idx_audit_ts (ts)
                    )
                    """);
        }
        safety = new WriteSqlSafety(new SqlSafety(List.of("accounts.password")), List.of("characters"));
        auditLog = new AuditLog(conSupplier);
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE characters");
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void call_validUpdate_writesRowAndAudit() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("INSERT INTO characters VALUES (1, 50, 'Foo')");
        }
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "UPDATE characters SET level = 51 WHERE id = 1");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("rows_affected").asInt());
        assertTrue(out.get("audit_id").asLong() > 0);
        try (Connection c = conSupplier.get(); var s = c.createStatement();
             var rs = s.executeQuery("SELECT level FROM characters WHERE id = 1")) {
            rs.next();
            assertEquals(51, rs.getInt(1));
        }
    }

    @Test
    void call_tableNotAllowed_rejected() {
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "UPDATE accounts SET password = 'x'");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void call_select_rejected() {
        DbExecuteTool tool = new DbExecuteTool(conSupplier, safety, auditLog, 5);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT * FROM characters");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
```

- [ ] **Step 2: Implement `DbExecuteTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.PreImageCapture;
import mcp.admin.WriteSqlSafety;
import mcp.data.SqlSafety;
import mcp.edit.EditLock;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.function.Supplier;

public class DbExecuteTool implements Tool {

    private static final int PRE_IMAGE_CAP = 100;

    private final Supplier<Connection> conSupplier;
    private final WriteSqlSafety safety;
    private final AuditLog auditLog;
    private final int timeoutSeconds;

    public DbExecuteTool(Supplier<Connection> conSupplier, WriteSqlSafety safety, AuditLog auditLog, int timeoutSeconds) {
        this.conSupplier = conSupplier;
        this.safety = safety;
        this.auditLog = auditLog;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() { return "cosmic.db.execute"; }

    @Override
    public String description() { return "Execute UPDATE/INSERT/DELETE on safelisted tables. Pre-image captured. Audited."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("sql").put("type", "string");
        props.putObject("params").put("type", "array");
        props.putObject("caller_note").put("type", "string");
        root.putArray("required").add("sql");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("sql") || !args.get("sql").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'sql'");
        }
        String sql = args.get("sql").asText();
        try {
            safety.check(sql);
        } catch (SqlSafety.UnsafeSqlException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        String callerNote = args.has("caller_note") && args.get("caller_note").isTextual() ? args.get("caller_note").asText() : null;

        if (!EditLock.INSTANCE.tryAcquire()) {
            throw new ToolException(McpError.SERVER_SHUTTING_DOWN, "edit_busy");
        }

        try (Connection con = conSupplier.get()) {
            con.setAutoCommit(false);
            try {
                JsonNode beforeJson;
                try {
                    beforeJson = PreImageCapture.capture(con, sql, PRE_IMAGE_CAP);
                } catch (SQLException e) {
                    throw new ToolException(McpError.INTERNAL_ERROR, "preimage failed: " + e.getMessage());
                }
                int affected;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setQueryTimeout(timeoutSeconds);
                    if (args.has("params") && args.get("params").isArray()) {
                        int i = 1;
                        for (JsonNode p : args.get("params")) {
                            if (p.isInt()) ps.setInt(i, p.asInt());
                            else if (p.isLong()) ps.setLong(i, p.asLong());
                            else if (p.isBoolean()) ps.setBoolean(i, p.asBoolean());
                            else ps.setString(i, p.asText());
                            i++;
                        }
                    }
                    affected = ps.executeUpdate();
                } catch (SQLTimeoutException e) {
                    con.rollback();
                    writeFailureAuditOutOfBand(callerNote, sql, "query_timeout");
                    throw new ToolException(McpError.QUERY_TIMEOUT, "query_timeout");
                } catch (SQLException e) {
                    con.rollback();
                    writeFailureAuditOutOfBand(callerNote, sql, e.getMessage());
                    throw new ToolException(McpError.INTERNAL_ERROR, "db.execute failed: " + e.getMessage());
                }

                ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
                argsJson.put("sql", sql);
                ObjectNode afterSummary = JsonRpc.MAPPER.createObjectNode();
                afterSummary.put("rows_affected", affected);
                AuditEntry entry = new AuditEntry(null, callerNote, "cosmic.db.execute",
                        argsJson, "rows_affected=" + affected, beforeJson, afterSummary.toString(), true);

                long auditId;
                try {
                    auditId = auditLog.insertInConnection(con, entry);
                } catch (SQLException e) {
                    con.rollback();
                    throw new ToolException(McpError.INTERNAL_ERROR, "audit write failed: " + e.getMessage());
                }
                con.commit();

                ObjectNode out = JsonRpc.MAPPER.createObjectNode();
                out.put("rows_affected", affected);
                out.put("audit_id", auditId);
                out.put("truncated_before", beforeJson != null && beforeJson.isObject()
                        && (beforeJson.has("capped") || beforeJson.has("warning")));
                return out;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        } finally {
            EditLock.INSTANCE.release();
        }
    }

    private void writeFailureAuditOutOfBand(String callerNote, String sql, String message) {
        try {
            ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
            argsJson.put("sql", sql);
            AuditEntry fail = new AuditEntry(null, callerNote, "cosmic.db.execute",
                    argsJson, message, null, null, false);
            auditLog.insert(fail);
        } catch (SQLException ignored) {
            // best-effort; failure audit is informational
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=DbExecuteToolTest -q
```
Expected: 3 tests PASS (or skip if no Docker).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/DbExecuteTool.java src/test/java/mcp/tools/DbExecuteToolTest.java
git commit -m "Add cosmic.db.execute MCP tool with audit + pre-image #minor"
```

---

## Task 14: `AuditListTool` (cosmic.admin.audit.list)

**Files:**
- Create: `src/main/java/mcp/tools/AuditListTool.java`
- Create: `src/test/java/mcp/tools/AuditListToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditListToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;
    static AuditLog auditLog;

    @BeforeAll
    static void up() throws SQLException {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available — AuditListToolTest skipped");
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE mcp_admin_audit (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      ts DATETIME(3) NOT NULL,
                      caller_ip VARCHAR(64),
                      caller_note VARCHAR(255),
                      tool VARCHAR(64) NOT NULL,
                      args_json JSON,
                      result_summary TEXT,
                      before_json JSON,
                      after_summary TEXT,
                      ok BOOLEAN NOT NULL,
                      INDEX idx_audit_ts (ts)
                    )
                    """);
        }
        auditLog = new AuditLog(conSupplier);
    }

    @AfterAll
    static void down() { if (mysql != null) mysql.stop(); }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("TRUNCATE TABLE mcp_admin_audit");
        }
    }

    @Test
    void call_returnsRecentEntries() throws Exception {
        for (int i = 0; i < 3; i++) {
            ObjectNode argsJson = JsonRpc.MAPPER.createObjectNode();
            argsJson.put("i", i);
            auditLog.insert(new AuditEntry("127.0.0.1", null, "cosmic.admin.run_command",
                    argsJson, "ok", null, null, true));
        }
        AuditListTool tool = new AuditListTool(conSupplier);
        JsonNode out = tool.call(JsonRpc.MAPPER.createObjectNode());
        assertEquals(3, out.get("entries").size());
    }

    @Test
    void call_filterByTool() throws Exception {
        auditLog.insert(new AuditEntry(null, null, "cosmic.admin.run_command",
                JsonRpc.MAPPER.createObjectNode(), null, null, null, true));
        auditLog.insert(new AuditEntry(null, null, "cosmic.db.execute",
                JsonRpc.MAPPER.createObjectNode(), null, null, null, true));
        AuditListTool tool = new AuditListTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("tool", "cosmic.db.execute");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("entries").size());
        assertEquals("cosmic.db.execute", out.get("entries").get(0).get("tool").asText());
    }

    @Test
    void call_invalidSinceIso_throws() {
        AuditListTool tool = new AuditListTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("since_iso", "not-a-date");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("invalid since_iso"));
    }

    private static boolean isDockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }
}
```

- [ ] **Step 2: Implement `AuditListTool`**

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

public class AuditListTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_ARGS_JSON_OUT = 4096;

    private final Supplier<Connection> conSupplier;

    public AuditListTool(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    @Override
    public String name() { return "cosmic.admin.audit.list"; }

    @Override
    public String description() { return "List MCP admin audit entries (recent first). Filterable by tool and time."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        props.putObject("tool").put("type", "string");
        props.putObject("since_iso").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        String tool = args.has("tool") && args.get("tool").isTextual() ? args.get("tool").asText() : null;
        Timestamp since = null;
        if (args.has("since_iso") && args.get("since_iso").isTextual()) {
            String s = args.get("since_iso").asText();
            try {
                since = Timestamp.from(Instant.parse(s));
            } catch (DateTimeParseException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "invalid since_iso: " + s);
            }
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, ts, caller_ip, caller_note, tool, args_json, result_summary, ok " +
                        "FROM mcp_admin_audit WHERE 1=1");
        if (tool != null) sql.append(" AND tool = ?");
        if (since != null) sql.append(" AND ts >= ?");
        sql.append(" ORDER BY ts DESC LIMIT ?");

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode entries = out.putArray("entries");
        try (Connection c = conSupplier.get();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            if (tool != null) ps.setString(idx++, tool);
            if (since != null) ps.setTimestamp(idx++, since);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode n = entries.addObject();
                    n.put("id", rs.getLong("id"));
                    n.put("ts", rs.getTimestamp("ts").toInstant().toString());
                    n.put("caller_ip", rs.getString("caller_ip"));
                    n.put("caller_note", rs.getString("caller_note"));
                    n.put("tool", rs.getString("tool"));
                    String aj = rs.getString("args_json");
                    n.put("args_json", aj == null ? null : truncate(aj, MAX_ARGS_JSON_OUT));
                    n.put("result_summary", rs.getString("result_summary"));
                    n.put("ok", rs.getBoolean("ok"));
                }
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "audit query failed: " + e.getMessage());
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...truncated]";
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=AuditListToolTest -q
```
Expected: 3 tests PASS (or skip if no Docker).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/AuditListTool.java src/test/java/mcp/tools/AuditListToolTest.java
git commit -m "Add cosmic.admin.audit.list MCP tool #minor"
```

---

## Task 15: Wire 7 tools into `Server.init()` + README + verification

**Files:**
- Modify: `src/main/java/net/server/Server.java`
- Modify: `README.md`

This is the integration task that builds the live `PlayerLookup`, `WorldDescribeTool.WorldStats` lists, and the not-supported command set against Cosmic's actual `Server` API.

- [ ] **Step 1: Conditionally register the 7 new tools**

Edit `src/main/java/net/server/Server.java`. After the existing `if (mcpConfig.editEnabled())` block but BEFORE `mcpServer = new McpServer(mcpConfig, new ToolRegistry(mcpTools));`, insert:

```java
                if (mcpConfig.adminEnabled()) {
                    java.util.function.Supplier<java.sql.Connection> dbConn = () -> {
                        try { return tools.DatabaseConnection.getConnection(); }
                        catch (java.sql.SQLException ex) { throw new RuntimeException(ex); }
                    };

                    mcp.admin.PlayerLookup playerLookup = buildPlayerLookup();
                    mcp.admin.AuditLog adminAuditLog = new mcp.admin.AuditLog(dbConn);
                    mcp.admin.CommandCatalog catalog = new mcp.admin.CommandCatalog(
                            client.command.CommandsExecutor.getInstance().getRegisteredCommands());
                    java.util.Set<String> notSupported = java.util.Set.of(
                            "warpme", "here", "where", "whereami", "summon", "goto"
                    );
                    mcp.admin.RunCommandExecutor runExec = new mcp.admin.RunCommandExecutor(catalog, notSupported::contains);

                    mcpTools.add(new mcp.tools.OnlineTool(playerLookup));
                    mcpTools.add(new mcp.tools.PlayerDescribeTool(playerLookup));
                    mcpTools.add(new mcp.tools.WorldDescribeTool(this::uptimeSeconds, this::worldStatsSnapshot));
                    mcpTools.add(new mcp.tools.CommandsListTool(catalog));
                    mcpTools.add(new mcp.tools.RunCommandTool(runExec, adminAuditLog));
                    mcpTools.add(new mcp.tools.AuditListTool(dbConn));

                    if (mcpConfig.dbExecuteEnabled() && !mcpConfig.sqlWritableTables().isEmpty()) {
                        mcp.admin.WriteSqlSafety writeSafety = new mcp.admin.WriteSqlSafety(
                                new mcp.data.SqlSafety(mcpConfig.sqlPiiDenylist()),
                                mcpConfig.sqlWritableTables());
                        mcpTools.add(new mcp.tools.DbExecuteTool(dbConn, writeSafety, adminAuditLog,
                                mcpConfig.sqlTimeoutSeconds()));
                    }
                }
```

- [ ] **Step 2: Add helper methods to `Server.java`**

Anywhere in the `Server` class body (e.g., near `buildNameIndex()`), add:

```java
private long uptimeSeconds() {
    return java.time.Duration.between(startTime != null ? startTime : java.time.Instant.now(),
            java.time.Instant.now()).toSeconds();
}

private java.util.List<mcp.tools.WorldDescribeTool.WorldStats> worldStatsSnapshot() {
    java.util.List<mcp.tools.WorldDescribeTool.WorldStats> out = new java.util.ArrayList<>();
    for (net.server.world.World w : getWorlds()) {
        int online = w.getPlayerStorage().getAllCharacters().size();
        config.WorldConfig wc = config.YamlConfig.config.worlds.get(w.getId());
        out.add(new mcp.tools.WorldDescribeTool.WorldStats(
                w.getId(),
                constants.game.GameConstants.WORLD_NAMES[w.getId()],
                wc == null ? 0 : wc.channels,
                online,
                wc == null ? 0 : wc.exp_rate,
                wc == null ? 0 : wc.meso_rate,
                wc == null ? 0 : wc.drop_rate
        ));
    }
    return out;
}

private mcp.admin.PlayerLookup buildPlayerLookup() {
    mcp.admin.PlayerLookup.OnlineProvider online = () -> {
        java.util.List<mcp.admin.PlayerLookup.Snapshot> list = new java.util.ArrayList<>();
        for (net.server.world.World w : getWorlds()) {
            for (client.Character chr : w.getPlayerStorage().getAllCharacters()) {
                list.add(new mcp.admin.PlayerLookup.Snapshot(
                        chr.getName(),
                        chr.getLevel(),
                        chr.getJob() == null ? 0 : chr.getJob().getId(),
                        chr.getExp(),
                        w.getId(),
                        chr.getClient() == null ? 0 : chr.getClient().getChannel(),
                        chr.getMapId(),
                        chr.getHp(),
                        chr.getMp(),
                        chr.getMeso(),
                        chr.gmLevel(),
                        true
                ));
            }
        }
        return list;
    };
    mcp.admin.PlayerLookup.OfflineLookup offline = name -> java.util.Optional.empty();
    return new mcp.admin.PlayerLookup(online, offline);
}
```

You will also need a `private final java.time.Instant startTime;` field initialized in `init()` near the existing `Instant beforeInit = Instant.now();` line:

```java
this.startTime = beforeInit;
```

If a `startTime` field doesn't exist, add `private java.time.Instant startTime;` near the top of the class fields. The offline lookup is left as `Optional.empty()` for v1 — Slice 3 v1 only describes online players via `player.describe`, and falls back to "no such player" for offline. A real DB-backed offline lookup is a small follow-up.

(Note: the exact getter names like `chr.getMeso()`, `chr.gmLevel()`, `WorldConfig.exp_rate` etc. should be confirmed against the actual code during this task. Adjust the names if the codebase differs.)

- [ ] **Step 3: Verify compile + full test suite**

```bash
./mvnw compile -q
./mvnw test -q 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: BUILD SUCCESS. Final summary line ~ `Tests run: 2060+, Failures: 0, Errors: 0`.

- [ ] **Step 4: Add README section**

Edit `README.md`. After the existing "MCP edit tools (Slice 2)" subsection, append:

```markdown

#### MCP admin tools (Slice 3)

Cosmic's MCP server can also inspect and mutate live game state, run any `@`-command, and execute `UPDATE`/`INSERT`/`DELETE` against a configurable allow-list of tables. Every mutation is recorded in a persistent `mcp_admin_audit` DB table. **Disabled by default**.

To enable, set `mcp.admin_enabled: true` in `config.yaml`. To enable DB writes, additionally set `mcp.db_execute_enabled: true` and populate `mcp.sql_writable_tables` with the tables you want to allow:

```yaml
mcp:
  admin_enabled: true
  db_execute_enabled: true
  sql_writable_tables:
    - characters
    - inventoryitems
```

Tools added: `cosmic.admin.online`, `cosmic.admin.player.describe`, `cosmic.admin.world.describe`, `cosmic.admin.commands.list`, `cosmic.admin.run_command`, `cosmic.db.execute`, `cosmic.admin.audit.list`. The `db.execute` tool registers only when both `db_execute_enabled` and `sql_writable_tables` are non-empty.

The `mcp_admin_audit` table is created via Liquibase the first time Cosmic boots after upgrade. The table's `before_json` column may contain row data including PII columns (e.g., `account.password`) — apply DB-level access controls accordingly.

Undo of recent admin actions is not implemented in v1; see `docs/superpowers/specs/2026-05-07-cosmic-mcp-slice-3-design.md` for the deferred Slice 3.5 plan.
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/Server.java README.md
git commit -m "Wire MCP admin tools into Server lifecycle + README #minor"
```

---

## Summary of new tools after Task 15

| # | Tool | Task |
|---|---|---|
| 20 | cosmic.admin.online | 5 |
| 21 | cosmic.admin.player.describe | 6 |
| 22 | cosmic.admin.world.describe | 7 |
| 23 | cosmic.admin.commands.list | 8 |
| 24 | cosmic.admin.run_command | 10 |
| 25 | cosmic.db.execute | 13 |
| 26 | cosmic.admin.audit.list | 14 |

All 7 spec'd tools accounted for. Tasks 1–4 build foundation (config, audit, catalog, lookup), 9 + 11 + 12 build mutation infrastructure, 15 wires everything in.

## Manual verification checklist (post-implementation)

- [ ] Set `mcp.admin_enabled: true`, `mcp.db_execute_enabled: true`, `mcp.sql_writable_tables: ["characters"]`. Boot Cosmic. Confirm Liquibase ran and `mcp_admin_audit` exists.
- [ ] `cosmic.admin.online` — confirm online list and filters.
- [ ] `cosmic.admin.player.describe {name: "<online>"}` — confirm online state.
- [ ] `cosmic.admin.world.describe` — confirm uptime + per-world stats.
- [ ] `cosmic.admin.commands.list {filter_substring: "kick"}` — confirm `@kick` appears.
- [ ] `cosmic.admin.run_command {command: "@broadcast hello"}` — confirm in-game broadcast appears; `cosmic.admin.audit.list` shows the entry.
- [ ] `cosmic.admin.run_command {command: "@warpme"}` — confirm `-32602 command requires in-game context`.
- [ ] `cosmic.db.execute {sql: "UPDATE characters SET level = level + 1 WHERE id = <test-char-id>"}` — confirm row updated; `cosmic.db.select` on `mcp_admin_audit` shows `before_json` with prior level.
- [ ] `cosmic.db.execute {sql: "UPDATE accounts SET password = 'x'"}` — confirm `-32602` (table not allowlisted, also PII).
- [ ] After `admin_enabled: false` restart — `tools/list` returns 19 tools (Slice 1 + 2 only).
