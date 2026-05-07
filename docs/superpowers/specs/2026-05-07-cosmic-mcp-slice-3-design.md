# Cosmic MCP Server — Slice 3 Design (Live Admin)

**Date:** 2026-05-07
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Live game-state read + mutation + DB writes via MCP, with persistent audit log.
**Builds on:** Slice 1 (read-only research, merged) and Slice 2 (content authoring, merged).

## Background

Slice 1 added 13 read-only research tools. Slice 2 added 6 git-tracked content-authoring tools. Slice 3 closes the loop by giving an MCP client read and write access to the *running* server: list online players, mutate live game state via a generic GM-command runner, and run DB writes against a configurable allow-list of tables. Every mutation is recorded in a new persistent audit table.

This is the highest-risk slice. It mutates live player state and the live database. Default is fully off; multiple gates protect against accidental enablement.

### Slice plan recap

1. **Slice 1 — Read-only research.** ✅ Shipped.
2. **Slice 2 — Content authoring.** ✅ Shipped.
3. **Slice 3 — Live admin (this spec).** Live read + GM-command runner + DB writes + audit log.
4. **Slice 3.5 (deferred) — Undo.** Replays inverse of recent audit entries to undo recent DB writes. Out of scope for v1.

## Goals

- Add 7 new MCP tools that let an MCP client inspect online state, dispatch existing GM commands, run write SQL against safelisted tables, and read the audit log.
- Reuse Slice 1's transport/auth/dispatcher and Slice 2's `EditLock` and `SqlSafety` infrastructure.
- Persist a tamper-evident-ish audit row for every mutation in a new `mcp_admin_audit` DB table.
- Default off. New `mcp.admin_enabled: false` gates the entire slice; `mcp.db_execute_enabled: false` and `mcp.sql_writable_tables: []` add belt-and-suspenders gates for DB writes.

## Non-goals

- **Undo.** Audit captures the inputs needed to build undo later (Slice 3.5). v1 ships audit only.
- **Per-token auth scopes.** Single bearer token continues to gate access. An optional `caller_note: string` argument on every mutation tool gives best-effort attribution in the audit log.
- **Wrapping every existing GM command as a typed tool.** Cosmic has 60+ `@`-commands across `gm0`–`gm6`. Wrapping each individually would dominate the slice. We use one **generic `cosmic.admin.run_command`** plus a **catalog tool** instead.
- **Mutation of game state outside the in-game GM command surface** (e.g., directly editing in-memory player objects). Use the audit-tracked SQL or `run_command` paths.

## Key decisions (from brainstorm)

| Decision | Choice | Why |
|---|---|---|
| Sub-slice combination | All three (3a + 3b + 3c) in one slice | User chose to ship all together. Internal decomposition keeps it tractable. |
| GM command coverage | Generic `run_command` + catalog tool | 1 wrapper covers all 60+ `@`-commands; ~50× less implementation than per-command wrappers; auto-tracks new commands. |
| Audit storage | New DB table `mcp_admin_audit` via Liquibase | Queryable from existing `cosmic.db.select`; survives restarts; one place to look. |
| Caller identity | Single bearer token + optional `caller_note` arg | Avoids token-management overhead; honor-system attribution; IP also captured. |
| Live DB write policy | `UPDATE`/`INSERT`/`DELETE` on `sql_writable_tables` allow-list | Extends Slice 1's `SqlSafety`; PII denylist still applies; pre-image captured (capped at 100 rows). |
| Undo support | Deferred to Slice 3.5 | Audit is the foundation; ship that first. |
| `run_command` execution | Synthesized GM-6 context dispatched through Cosmic's existing `CommandsExecutor` | Reuses every existing command unchanged. Commands that require a calling-Character context return INVALID_PARAMS with a clear message. |
| Default state | All gates off; `sql_writable_tables` empty | Existing deployments are unaffected by upgrade. |

## Architecture

### Where it lives

New code lives under `src/main/java/mcp/admin/` plus 7 tool classes under `src/main/java/mcp/tools/`. The MCP module's existing `McpServer`, `HttpJsonRpcHandler`, `McpDispatcher`, `ToolRegistry`, and `EditLock` are reused unchanged. A new Liquibase changeset adds the `mcp_admin_audit` table.

### Lifecycle and registration gate

`net.server.Server.init()`'s MCP boot block conditionally appends the 7 new tools when `mcpConfig.adminEnabled()` is true. `cosmic.db.execute` has additional gates: it registers only when `db_execute_enabled: true` AND `sql_writable_tables` is non-empty. The dispatcher's `tools/list` simply omits unregistered tools.

```java
if (mcpConfig.adminEnabled()) {
    mcpTools.add(new mcp.tools.OnlineTool());
    mcpTools.add(new mcp.tools.PlayerDescribeTool());
    mcpTools.add(new mcp.tools.WorldDescribeTool());
    mcpTools.add(new mcp.tools.CommandsListTool());
    mcpTools.add(new mcp.tools.RunCommandTool(auditLog));
    mcpTools.add(new mcp.tools.AuditListTool(dbConnSupplier));
    if (mcpConfig.dbExecuteEnabled() && !mcpConfig.sqlWritableTables().isEmpty()) {
        mcpTools.add(new mcp.tools.DbExecuteTool(dbConnSupplier, writeSafety, auditLog));
    }
}
```

### Components

```
src/main/java/mcp/admin/
  AuditLog.java              Inserts rows into mcp_admin_audit; size-bounds args/before
  AuditEntry.java            Record type passed to AuditLog
  RunCommandExecutor.java    Synthesizes GM-6 context; dispatches to Cosmic CommandsExecutor
  PlayerLookup.java          Online-by-name + offline-DB-fallback helpers
  WorldSnapshot.java         Capture of worlds/channels/online counts
  CommandCatalog.java        Reflection scan of client.command.commands.gm*; built once
  WriteSqlSafety.java        Extends Slice 1 SqlSafety: UPDATE/INSERT/DELETE on writable allow-list
  PreImageCapture.java       SELECT-before-write for db.execute audit (cap 100 rows)
  AdminConfig.java           Typed view of admin_enabled / db_execute_enabled / sql_writable_tables

src/main/java/mcp/tools/
  OnlineTool.java            20: cosmic.admin.online
  PlayerDescribeTool.java    21: cosmic.admin.player.describe
  WorldDescribeTool.java     22: cosmic.admin.world.describe
  CommandsListTool.java      23: cosmic.admin.commands.list
  RunCommandTool.java        24: cosmic.admin.run_command
  DbExecuteTool.java         25: cosmic.db.execute
  AuditListTool.java         26: cosmic.admin.audit.list

src/main/java/config/
  McpConfigYaml.java         + admin_enabled, db_execute_enabled, sql_writable_tables

src/main/java/mcp/config/
  McpConfig.java             + adminEnabled, dbExecuteEnabled, sqlWritableTables

src/main/resources/db/changelog/
  YYYYMMDD-mcp-admin-audit.xml   Liquibase changeset adding the audit table
```

### Audit table

Liquibase changeset adds:

```sql
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
);
```

### Reused infrastructure

- `EditLock` from Slice 2 — DB writes serialize alongside file edits.
- `SqlSafety` from Slice 1 — `WriteSqlSafety` extends/composes it (PII denylist still applies on top of the new write-table allow-list).
- `tools.DatabaseConnection` from Cosmic — same Hikari pool; we open transactions per `db.execute` call.

### `run_command` execution model

`RunCommandExecutor` parses the leading `@<name>` token, looks up the corresponding `Command` subclass via `CommandCatalog`, and dispatches it through Cosmic's existing `CommandsExecutor`. To run without a real player session, it synthesizes a minimal admin `Client`/`Character` context with `gmLevel = 6`. The command's chat-output channel is captured via a custom `Client` subclass that records messages instead of sending packets.

**Known-not-supported commands.** Some commands rely on the calling character's map, position, or party membership (e.g., `@warpme`, `@here`, `@summon` without args). These return `-32602 "command requires in-game context: <name>"`. The exact list is enumerated during implementation by inspecting commands that read `Client.getPlayer().getMap()` or similar caller-context state. Documented in code comments alongside `RunCommandExecutor`.

## Tool surface

All inputs schemas use `additionalProperties: false`. Names follow `cosmic.admin.<verb>` or `cosmic.db.<verb>` like prior slices.

### 20. `cosmic.admin.online`

**Input:** `{ world?: int, channel?: int, map?: int, name_substring?: string, limit?: int (1..200, default 100) }`
**Returns:** `{ players: [{name, level, job, world, channel, map, hp, mp}], total: int }`
**Source:** Iterates `Server.getInstance().getWorlds()` → `world.getPlayerStorage().getAllCharacters()`. Filters in memory. **Read-only, no audit row.**

### 21. `cosmic.admin.player.describe`

**Input:** `{ name: string }`
**Returns:** `{ name, level, exp, job, world, channel, map, hp, mp, mesos, gm_level, online: bool, last_login? }`. For online players includes a basic inventory summary (counts per inventory type, no item-level detail). For offline players, queries the `characters` table for the canonical fields.
**Source:** `PlayerLookup`: try online first; fall back to a SELECT on `characters` (via existing `db.select` machinery internally). **Read-only, no audit row.**

### 22. `cosmic.admin.world.describe`

**Input:** `{}`
**Returns:** `{ uptime_seconds, worlds: [{id, name, channels: int, online_count, exp_rate, meso_rate, drop_rate}] }`
**Source:** `Server.getInstance()` accessors + `YamlConfig.config.worlds`. **Read-only, no audit row.**

### 23. `cosmic.admin.commands.list`

**Input:** `{ filter_substring?: string, gm_level?: int (0..6) }`
**Returns:** `{ commands: [{name, gm_level, syntax, description}] }`
**Source:** `CommandCatalog`: at MCP startup, reflection-scans `client.command.commands.gm0`..`gm6` packages; collects each `Command` subclass's command name (from `getCommand()`) and its declared GM level (from package). Description and syntax come from a Javadoc / `getUsage()` fallback; if neither is present, syntax is `@<name>` and description is empty. **Read-only, no audit row.**

### 24. `cosmic.admin.run_command`

**Input:** `{ command: string (e.g. "@kick playerX"), as_gm_level?: int (0..6, default 6), caller_note?: string }`
**Returns:** `{ ok: bool, output: string, audit_id: long }`

Behavior:
1. Parse leading `@<name>` from `command`. Unknown name → `-32602 "unknown command: <name> (use cosmic.admin.commands.list)"`.
2. Lookup the `Command` subclass.
3. If the command is in the not-supported list → `-32602 "command requires in-game context: <name>"`.
4. Synthesize an admin context with `as_gm_level` and dispatch through Cosmic's `CommandsExecutor`.
5. Capture chat output (the command's `c.sendMessage(...)` calls) via the synthesized `Client`.
6. Insert audit row: `tool="cosmic.admin.run_command"`, `args_json={"command": "...", "as_gm_level": N}`, `before_json=null`, `result_summary=output[:1000]`, `ok=true`.
7. Return `{ok: true, output, audit_id}`.

If the command itself throws (Cosmic's executor lets exceptions through), audit row is written with `ok=false` and the tool returns `-32603 "command failed: <message>"`. **Audit row always written even on failure.**

### 25. `cosmic.db.execute`

**Input:** `{ sql: string, params?: any[], caller_note?: string }`
**Returns:** `{ rows_affected: int, audit_id: long, truncated_before: bool }`

Behavior:
1. `WriteSqlSafety.check(sql)`:
   - Parses with JSqlParser (existing).
   - Rejects multi-statement.
   - Accepts only `UPDATE` / `INSERT` / `DELETE` (rejects DDL, SELECT, TRUNCATE, etc.).
   - Extracts target table name. Rejects if not in `mcp.sql_writable_tables`.
   - Applies the existing PII denylist from Slice 1's `SqlSafety`.
2. Acquires `EditLock` (shared with Slice 2 file edits; tryLock 2s; on failure → `-32000 edit_busy`).
3. **Pre-image capture** (in `PreImageCapture`):
   - For `UPDATE`/`DELETE` with a WHERE clause: `SELECT * FROM <table> WHERE <same predicate> LIMIT 101`. If 101 rows returned → `truncated_before=true`, before-image stored as `{"capped": true, "row_count_at_least": 100}`.
   - For `UPDATE`/`DELETE` without a WHERE clause (full-table): `truncated_before=true`, before-image stored as `{"warning": "no_where_clause"}`. The audit captures intent without the data.
   - For `INSERT`: `before_json=null`. (No prior rows.)
4. Opens a JDBC transaction.
5. Executes the user's SQL with `setQueryTimeout(mcp.sql.timeout_seconds)`.
6. Inserts the audit row in the same transaction. If the audit insert fails → rollback both. (User's SQL did NOT take effect.)
7. Commits.
8. Returns `{rows_affected, audit_id, truncated_before}`.

If the user's SQL fails (constraint violation, timeout, etc.) → rollback; **also write an `ok=false` audit row in a separate auto-commit connection** so failures are recorded. Returns `-32603 "db.execute failed: <msg>"` or `-32001 "query_timeout"`.

### 26. `cosmic.admin.audit.list`

**Input:** `{ limit?: int (1..200, default 50), tool?: string, since_iso?: string }`
**Returns:** `{ entries: [{id, ts, caller_ip, caller_note, tool, args_json, result_summary, ok}] }`

Source: `SELECT id, ts, caller_ip, caller_note, tool, args_json, result_summary, ok FROM mcp_admin_audit WHERE (?::tool IS NULL OR tool = ?) AND (?::since IS NULL OR ts >= ?) ORDER BY ts DESC LIMIT ?`. **Does NOT expose `before_json` or `after_summary`** — the former can be large and the latter is freeform; for direct inspection use `cosmic.db.select` with appropriate column projections.

`args_json` truncated to 4 KB on output (`...truncated]` suffix appended). `result_summary` truncated to 1 KB.

If `since_iso` is unparseable → `-32602 "invalid since_iso: <value>"`.

## Configuration additions

```yaml
mcp:
  ...existing keys...
  admin_enabled: false
  db_execute_enabled: false
  sql_writable_tables: []
```

`McpConfigYaml` adds `boolean admin_enabled`, `boolean db_execute_enabled`, `List<String> sql_writable_tables`. `McpConfig` record adds the matching components, with the same blank-or-null fallbacks as prior slices.

## Error handling

| Condition | Code | Message |
|---|---|---|
| Player name not found (online or DB) | -32602 | "no such player: <name>" |
| Unknown command | -32602 | "unknown command: <name> (use cosmic.admin.commands.list)" |
| Command requires in-game context | -32602 | "command requires in-game context: <name>" |
| `db.execute` non-write statement | -32602 | "db.execute is for UPDATE/INSERT/DELETE; use db.select for reads" |
| `db.execute` table not in allow-list | -32602 | "table not writable: <table>" |
| `db.execute` PII column referenced | -32602 | "denied column: <table.column>" |
| `db.execute` empty `sql_writable_tables` (tool registered but config invalidated post-boot — defensive) | -32602 | "no writable tables configured" |
| `audit.list` since_iso unparseable | -32602 | "invalid since_iso: <value>" |
| Audit row write failure (mutation context) | -32603 | "audit write failed: <msg>" — DB writes rolled back; game-state mutations log CRITICAL |
| User SQL execution failure | -32603 | "db.execute failed: <msg>" — audit row inserted with `ok=false` in separate transaction |
| User SQL timeout | -32001 | "query_timeout" — audit `ok=false` |
| `EditLock` not acquired in 2s | -32000 | "edit_busy" |

Stack traces stay in server logs. Clients receive sanitized messages.

## Observability

Reuses existing log4j2 config. Per-mutation INFO line:

```
mcp_admin tool=<name> caller=<ip> note=<caller_note> audit_id=<id> ok=<bool>
```

Plus existing per-request log from Slice 1. No metrics in v1.

## Testing

### Unit tests (JUnit 5)

- `WriteSqlSafety` — accepts UPDATE/INSERT/DELETE on safelisted tables; rejects DDL, SELECT, multi-statement, non-allowlisted tables, PII columns. Rejects empty allow-list.
- `PreImageCapture` — given fixture DB rows: ≤100 rows returns full payload; >100 returns capped marker; no WHERE returns warning marker; INSERT returns null.
- `OnlineTool` — given a mocked `Server` with fake online players across worlds/channels/maps, filters return correct subsets.
- `PlayerDescribeTool` — online-first lookup; falls back to DB SELECT for offline.
- `WorldDescribeTool` — given mocked `Server` and config, returns expected shape.
- `CommandCatalog` — reflection scan against the actual `gm0`–`gm6` packages; asserts well-known commands like `@kick`, `@item`, `@whoonline` are present with the right GM level.
- `CommandsListTool` — passes filter/gm_level args; verifies output ordering and structure.
- `RunCommandExecutor` — uses a stub `CommandsExecutor` (no real Cosmic dispatch); verifies command parsing, not-supported list rejection, output capture.
- `RunCommandTool` — verifies audit row is written with correct shape on both success and command failure.
- `DbExecuteTool` — Testcontainers MySQL (skipped without Docker, same pattern as Slice 1):
  - happy path UPDATE; pre-image captured; audit row written; rows_affected returned
  - INSERT — no pre-image; audit row written
  - DELETE with WHERE — pre-image captured
  - allow-list rejection (table not in `sql_writable_tables`)
  - PII rejection (column in denylist)
  - non-write statement (SELECT) rejection
  - audit insert failure → user SQL rolled back (forced by injecting a failing audit log mock)
  - user SQL timeout → audit `ok=false` row written separately
- `AuditListTool` — Testcontainers; insert N audit rows with varying tools and timestamps; query with `tool` and `since_iso` filters; verify pagination cap and `args_json` truncation.

### Integration / manual verification (in implementation plan's final task)

- Boot Cosmic with `admin_enabled: true`, `db_execute_enabled: true`, `sql_writable_tables: ["characters"]`.
- `cosmic.admin.online` — confirm online players returned; world/channel filters work.
- `cosmic.admin.player.describe` for a known online player and a known offline character.
- `cosmic.admin.world.describe` — confirm uptime and per-world rates.
- `cosmic.admin.commands.list` — confirm `@kick`, `@item`, `@whoonline` are present.
- `cosmic.admin.run_command` with `command: "@broadcast hello"` — confirm in-game broadcast appears; audit row created.
- `cosmic.admin.run_command` with a known not-supported command — confirm clean rejection.
- `cosmic.db.execute` with `UPDATE characters SET level = level + 1 WHERE id = <test-char-id>` — confirm row updated; `cosmic.db.select` on `mcp_admin_audit` shows before_json with prior level.
- `cosmic.db.execute` with `UPDATE accounts SET password = 'x'` — rejected (table not allowlisted, also PII column).
- `cosmic.admin.audit.list` — confirm recent entries returned; `since_iso` filter works.
- After `admin_enabled: false` restart — `tools/list` returns 19 tools (Slice 1+2 only).

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Operator enables `admin_enabled` and forgets `sql_writable_tables` is empty | Belt-and-suspenders: `db.execute` registers only when both `db_execute_enabled` and non-empty allow-list are set. |
| Bug in `RunCommandExecutor` synthesis crashes a player session | The synthesized client/character is isolated; it never joins a real channel/map. Worst case the dispatched command targets a real player and behaves the same as a real `@kick` would. |
| Audit table grows unbounded | Spec calls out `idx_audit_ts`. Operator runs their own retention (`DELETE FROM mcp_admin_audit WHERE ts < NOW() - INTERVAL 30 DAY`). v1 does not include rotation tooling. |
| Pre-image SELECT is slow on a non-indexed WHERE | Limited to 101 rows; same query timeout as the user's UPDATE/DELETE. Worst case is `query_timeout` and rollback. |
| Pre-image SELECT exposes PII columns to before_json | The pre-image SELECT runs `SELECT *` to capture full rows. PII denylist guards the *user's input SQL* but NOT the pre-image SELECT. Audit before_json may contain PII columns (e.g., `account.password`). Documented; operator should treat audit table as sensitive and apply DB-level access controls. **Important caveat — flag in README.** |
| `caller_note` is honor-system | Documented. Multi-user setups should use Slice 3.5 / future scoped tokens. |

## Out of scope

- Undo (Slice 3.5).
- Per-token scopes / multi-user attribution.
- Wrapping each existing GM command as a typed tool.
- Audit table rotation / retention tooling.
- Live mutation paths outside `run_command` and `db.execute` (e.g., direct in-memory player edits).
- Exposing `before_json` via `audit.list`.

## Open questions for implementation

- The exact list of "not-supported" commands is determined during implementation by code-reading the `gm*` Command subclasses for caller-context dependencies (`Client.getPlayer().getMap()`, `getPosition()`, party membership, etc.). Documented in `RunCommandExecutor` as a static set.
- The Liquibase changeset filename uses today's date prefix following the project's existing convention (check `src/main/resources/db/changelog/`); align during implementation.
- Confirm `CommandCatalog` reflection works in the fat-jar deployment; if classpath scanning is awkward for a packaged JAR, fall back to a hand-maintained list initialized from the same `gm*` packages.
