# Cosmic MCP Server — Slice 1 Design

**Date:** 2026-05-07
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Read-only research MCP for the Cosmic MapleStory v83 server.

## Background

Cosmic is a Java 21 server emulator for GMS v83. We want an MCP server that lets a Claude client (Claude Code on a developer laptop) answer high-level questions about Cosmic's data and code: items, mobs, maps, NPCs, quests, drops, scripts, packet handlers, DB schema, config.

The user's eventual goal is "all of the above": live admin, data lookup, content authoring, and codebase navigation. We decomposed that into three slices and are designing the first.

### Slice plan
1. **Slice 1 (this spec):** Read-only research over WZ XML, MySQL (read-only), `scripts/`, `src/main/java/`, and `config.yaml`.
2. **Slice 2 (future spec):** Content authoring — edit JS scripts, tune drops/rates, modify `config.yaml` with reviewable diffs.
3. **Slice 3 (future spec):** Live server admin — query and mutate live game state via an in-process JVM RPC.

Each slice is its own brainstorm → spec → plan → implementation cycle.

## Goals

- Expose 13 named MCP tools (listed below) that answer common research queries about Cosmic.
- Run as an in-process module of Cosmic's existing JVM, reusing loaded WZ providers and the Hikari DB pool.
- Be reachable over HTTP from a developer laptop on the same LAN, with bearer-token auth.
- Add only the minimum new state — no parallel WZ index — by reusing Cosmic's `provider/` and existing factories.

## Non-goals

- Mutations of game state, DB rows, or files. (Slice 2/3.)
- Public-internet exposure. LAN-only with bearer token.
- Multi-tenant auth. Single-token, single-user.
- Sidecar / out-of-process variant. The MCP runs inside Cosmic's JVM.

## Key decisions (from brainstorm)

| Decision | Choice | Why |
|---|---|---|
| Process topology | In-process JVM module | Direct access to loaded providers and DB pool; sets up Slice 3. |
| Transport | HTTP (Streamable HTTP per MCP spec) over Netty | Netty already a Cosmic dependency; no new HTTP framework. |
| Network surface | LAN bind, bearer token, optional TLS | User's setup is dev box on LAN, no VPN. |
| Data access | Reuse Cosmic providers + tiny auxiliary indices | Avoids parallel WZ index; smallest code footprint. |
| SQL policy (tool 12) | Arbitrary `SELECT` with timeout, row cap, PII denylist | User chose flexibility over safe-listing. |
| Failure tolerance | MCP startup failure is logged, not fatal | Game server stays up if MCP can't start. |

## Architecture

### Where it lives

New package `src/main/java/mcp/` inside Cosmic. Compiled into the same fat jar.

### Lifecycle

`net.server.Server` constructs an `McpServer` after providers are warm and `DatabaseConnection` is initialized. On Server shutdown, `McpServer.stop()` is called; in-flight requests complete or are rejected with `-32000 server_shutting_down`.

If `McpServer.start()` throws, the exception is logged at WARN and the game server continues.

### Components

```
mcp/
  McpServer.java                    // boot/shutdown, owns Netty channel
  transport/
    HttpJsonRpcHandler.java         // Streamable HTTP transport (POST /mcp, GET /mcp for SSE)
    AuthFilter.java                 // bearer token check, constant-time compare
  protocol/
    McpDispatcher.java              // tools/list, tools/call routing
    ToolRegistry.java               // registers all 13 tools
  tools/
    ItemTool.java                   // 1 cosmic.item.describe
    MobTool.java                    // 2 cosmic.mob.describe
    MapTool.java                    // 3 cosmic.map.describe
    NpcTool.java                    // 4 cosmic.npc.describe
    QuestTool.java                  // 5 cosmic.quest.describe
    SkillTool.java                  // 6 cosmic.skill.describe
    DropSearchTool.java             // 7 cosmic.drop.search
    NameSearchTool.java             // 8 cosmic.name.search
    ScriptFinderTool.java           // 9 cosmic.script.find
    JavaCodeSearchTool.java         // 10 cosmic.code.search
    SchemaTool.java                 // 11 cosmic.db.schema
    SqlSelectTool.java              // 12 cosmic.db.select
    ConfigInspectTool.java          // 13 cosmic.config.get
  data/
    DropIndex.java                  // forward + reverse drop index, built once at startup
    NameIndex.java                  // String.wz name index, built once at startup
    SqlSafety.java                  // JSqlParser AST checks + denylist + caps
```

### Dependencies on existing Cosmic code

- `server.ItemInformationProvider` — item metadata.
- `provider.DataProviderFactory` / `provider.DataProvider` — generic WZ XML access.
- `server.life.LifeFactory` — mob and NPC metadata (small read-only getters added if missing).
- `server.maps.MapleMapFactory` — map metadata.
- `tools.DatabaseConnection` — Hikari pool.
- `config.YamlConfig` (or equivalent) — already loaded `config.yaml`.

We add small read-only getters where a provider doesn't already expose what we need. We do **not** maintain a parallel general-purpose WZ index. Two narrow auxiliary indices (`DropIndex`, `NameIndex`) are built once at startup to answer queries Cosmic's existing providers can't serve efficiently — these are scoped, not a duplicate WZ cache.

### New runtime dependencies

- `io.modelcontextprotocol.sdk:mcp` — Java MCP SDK. To confirm at implementation time that the plain (non-Spring) variant is suitable; otherwise we hand-roll JSON-RPC 2.0 over Netty.
- `com.github.jsqlparser:jsqlparser` — SQL AST for tool 12 safety checks.

## Transport and auth

**Endpoint:** `POST /mcp` for JSON-RPC requests; `GET /mcp` reserved for SSE. Slice 1 returns all results inline; SSE stays wired but unused.

**Bind:** `mcp.bind_addr` from `config.yaml`. User binds to LAN IP. Default `0.0.0.0:8765` is documented as unsafe on a public host.

**Auth:** static bearer token in `mcp.auth_token`. `AuthFilter` rejects requests without `Authorization: Bearer <token>` with HTTP 401. Comparison via `MessageDigest.isEqual` (constant-time). Server refuses to start if token is missing, blank, or shorter than 16 characters.

**TLS:** off by default. If `mcp.tls.cert` and `mcp.tls.key` are both set, Netty wraps with `SslContextBuilder.forServer`. Self-signed acceptable.

**Rate limiting:** none in v1. LAN, single user, token-gated.

**Shutdown semantics:** dispatcher checks a `running` flag; in-flight requests during shutdown receive `-32000 server_shutting_down`.

**Client config example** (Claude Code `.mcp.json`):

```json
{
  "mcpServers": {
    "cosmic": {
      "type": "http",
      "url": "http://<lan-ip>:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

## Tool surface

All 13 tools. Names follow `cosmic.<group>.<verb>`. Inputs validated against JSON Schema; unknown fields rejected.

| # | Tool | Input | Returns | Source |
|---|---|---|---|---|
| 1 | `cosmic.item.describe` | `{ id: int }` | name, category, sell price, slotMax, stats, reqLevel, reqJob | `ItemInformationProvider` + WZ Item.wz |
| 2 | `cosmic.mob.describe` | `{ id: int }` | level, HP/MP, EXP, undead/boss flags, drops | `LifeFactory` + WZ Mob.wz + `drop_data` |
| 3 | `cosmic.map.describe` | `{ id: int }` | name, NPCs, spawns, portals, returnMap, fieldType | `MapleMapFactory` + WZ Map.wz + String.wz |
| 4 | `cosmic.npc.describe` | `{ id: int }` | name, maps, scriptPath | WZ Npc.wz + String.wz + `scripts/npc/` scan |
| 5 | `cosmic.quest.describe` | `{ id: int }` | name, requirements, rewards, scriptPath | WZ Quest.wz + `scripts/quest/` |
| 6 | `cosmic.skill.describe` | `{ id: int }` | job, max level, per-level effects | WZ Skill.wz |
| 7 | `cosmic.drop.search` | `{ mob_id?: int, item_id?: int }` (one required) | `[{mob, item, chance, min, max, source}]` | `DropIndex` over `drop_data` + `global_drop_data` + `reactordrops_data` |
| 8 | `cosmic.name.search` | `{ query: string, kind?: "item"\|"mob"\|"map"\|"npc"\|"skill", limit?: int<=100 }` | `[{kind, id, name}]` | `NameIndex` (String.wz) |
| 9 | `cosmic.script.find` | `{ query: string }` | `[{file, line, snippet}]` | filesystem scan of `scripts/` |
| 10 | `cosmic.code.search` | `{ query: string, kind?: "opcode"\|"text", limit?: int }` | `[{file, line, snippet}]` | filesystem scan of `src/main/java/`; opcode mode resolves hex/int to `RecvOpcode`/`SendOpcode` constant first, then greps |
| 11 | `cosmic.db.schema` | `{ table?: string }` | tables list, or columns + FKs for one table | `INFORMATION_SCHEMA` |
| 12 | `cosmic.db.select` | `{ sql: string, params?: any[] }` | `{ rows, columns, truncated }` (≤1000 rows) | Hikari read-only conn, `setQueryTimeout(5)`, `SqlSafety` |
| 13 | `cosmic.config.get` | `{ path: string }` (e.g. `worlds[0].exp_rate`) | value at path | parsed `config.yaml` |

### Pagination

All list-returning tools accept `limit` (default 50, max 100) and `offset`. Drop and name search require this to keep token budgets sane.

### `DropIndex` (tool 7)

Built once at MCP startup. Joins:
- `drop_data` — per-mob drops
- `global_drop_data` — drops attached to any mob in a level/map range
- `reactordrops_data` — drops from reactors

Stored as two `Map<Integer, List<DropEntry>>`: by mob ID and by item ID. Memory cost: hundreds of KB. Rebuild capability is documented but not exposed in Slice 1.

### `NameIndex` (tool 8)

At startup, walks `String.wz` once and builds `Map<Kind, List<{id, lowercaseName}>>`. Search is in-memory `contains` ranked by prefix match. Cheap.

### `SqlSafety` (tool 12)

Concrete enforcement, in order:
1. Parse SQL with JSqlParser. Reject if not exactly one statement, or if not a `SELECT`/`WITH ... SELECT`.
2. Walk AST. If any referenced column matches the `pii_denylist` config (table.column form), reject with `-32602 invalid_params, "denied column: <name>"`.
3. Open connection with `setReadOnly(true)`.
4. `Statement.setQueryTimeout(timeout_seconds)`.
5. Iterate `ResultSet` up to `row_cap`; if more rows exist, set `truncated=true` and stop.

The denylist is config-driven, editable without code changes. Initial entries: `account.password`, `account.pin`, `account.pic`, `account.email`, `account.tos`.

### `cosmic.code.search` opcode mode

Input like `{ query: "0x6C", kind: "opcode" }` or `{ query: "108", kind: "opcode" }`:
1. Look up `RecvOpcode` / `SendOpcode` enum sources in `src/main/java/net/`.
2. Find the constant whose value matches.
3. Grep that constant's name across `src/main/java/`.
4. Return matches.

This is the semantic value-add over Claude's native Grep.

## Configuration

Additions to `config.yaml`:

```yaml
mcp:
  enabled: true
  bind_addr: "192.168.1.42"
  port: 8765
  auth_token: "<required, 16+ chars>"
  tls:
    cert: ""
    key: ""
  sql:
    enabled: true
    timeout_seconds: 5
    row_cap: 1000
    pii_denylist:
      - "account.password"
      - "account.pin"
      - "account.pic"
      - "account.email"
      - "account.tos"
  request_log: true
```

`McpServer` validates on boot and refuses to start if `enabled=true` and `auth_token` is missing or shorter than 16 characters.

## Error handling

- Unknown ID (item/mob/map/etc.): `-32602 invalid_params, "no such <kind>: <id>"`.
- PII column referenced in SQL: `-32602 invalid_params, "denied column: <name>"`.
- SQL non-SELECT or multi-statement: `-32602 invalid_params, "only single SELECT allowed"`.
- SQL timeout: `-32000 query_timeout`.
- Internal exception: `-32000` with sanitized message; no stack trace returned to client.
- Auth failure: HTTP 401 before JSON-RPC layer.
- Server shutting down: `-32000 server_shutting_down`.

Stack traces stay in the server log only.

## Observability

SLF4J via Cosmic's existing log4j2 config. One INFO line per request:

```
mcp tool=<name> caller=<ip> dur_ms=<n> ok=<true|false>
```

Errors at WARN with cause. No new appender. No metrics in v1; dispatcher leaves hook points for Prometheus later.

## Testing

### Unit (JUnit 5 + Mockito)

- Each tool's input parsing and parameter validation.
- `SqlSafety`: rejects non-SELECT, multi-statement, denylisted columns; passes legitimate selects.
- `DropIndex`: forward and reverse lookups against fixture rows.
- `NameIndex`: ranked search against fixture entries.
- `AuthFilter`: rejects missing/wrong/short tokens; constant-time compare verified.
- `code.search` opcode resolution: known opcode → known constant name.

### Integration

- Boot `McpServer` against testcontainers MySQL for `db.*` tools.
- WZ tools use small fixture XML (no testcontainers needed).
- End-to-end: HTTP request → JSON-RPC parse → tool dispatch → result, exercised for one happy-path tool and one error-path tool.

Adding `org.testcontainers:mysql` as a `<scope>test</scope>` dep is acceptable.

### Manual verification checklist

- Tail `server.log` while issuing each tool from a real Claude Code session.
- Confirm an unauthorized request returns HTTP 401.
- Confirm `SELECT password FROM account` is rejected with the PII error.
- Confirm SQL timeout fires for a deliberately slow query.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| MCP bug crashes the JVM (in-process) | All tool calls wrapped in try/catch; uncaught exceptions return JSON-RPC errors. No tool blocks shutdown. |
| Java MCP SDK is awkward without Spring | Fallback: hand-roll JSON-RPC 2.0 over Netty. Decided at implementation time; spec is SDK-agnostic. |
| WZ XML changes invalidate `DropIndex`/`NameIndex` | Indices are built at startup. WZ files don't change at runtime. Restart rebuilds them. |
| Bearer token leaks | LAN only; rotate by editing `config.yaml` and restarting. Slice 3 may add per-token scopes. |
| Public-host misconfiguration | Document `0.0.0.0` warning in config comments; consider startup check that warns if bound non-loopback without TLS. |

## Out of scope (deferred to later slices or follow-ups)

- Live game-state queries (online players, channel state) — Slice 3.
- Mutations of any kind — Slice 2/3.
- Multi-token / per-scope auth — future.
- Prometheus metrics — future.
- Hot reindex of `DropIndex`/`NameIndex` without restart — future.
- Sidecar / out-of-process MCP variant — explicitly not chosen.

## Open questions for implementation

- Confirm whether `io.modelcontextprotocol.sdk:mcp` works cleanly without Spring, or fall back to hand-rolled JSON-RPC.
- Confirm exact provider classes for mob/map/quest read paths and whether new read-only getters are needed.
- Confirm the `RecvOpcode`/`SendOpcode` enum location for `code.search` opcode mode.

These are tactical and resolved during the implementation plan.
