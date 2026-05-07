# Cosmic MCP Server — Slice 2 Design (Content Authoring)

**Date:** 2026-05-07
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Git-tracked content authoring via MCP — JS scripts, `config.yaml`, drop-data SQL files, plus three small git workflow tools.
**Builds on:** Slice 1 (`docs/superpowers/specs/2026-05-07-cosmic-mcp-design.md`), merged on master via PR #1.

## Background

Slice 1 added 13 read-only MCP tools (item / mob / map / npc / quest / skill describe, drop and name search, script and code search, db schema and SELECT, config inspect). Slice 2 adds the *write* counterpart for git-tracked surfaces. The user wants to ask Claude in chat: "update NPC 9201000 to give item X instead of Y" — and have Claude propose a diff, write it to disk on approval, and (optionally) commit it, all without leaving the chat.

### Slice plan recap

1. **Slice 1 — Read-only research.** ✅ Shipped on master (PR #1).
2. **Slice 2 — Content authoring (this spec).** Edit JS scripts, `config.yaml`, and drop-data SQL files. Plus `cosmic.git.diff` / `commit` / `revert` to round out the chat-only workflow.
3. **Slice 3 — Live server admin (future).** Mutate live game state and the live DB. Needs auth scopes, audit log, undo. Out of scope here.

A small **Slice 2.5** was deferred during this brainstorm: live DB writes (`UPDATE drop_data SET …` against the running database). That work needs its own audit/undo design and is folded into the Slice 3 brainstorm or a follow-up.

## Goals

- Add 6 new MCP tools that let an MCP client edit specific git-tracked surfaces and drive a basic git workflow.
- Reuse Slice 1's in-process module, transport, auth, dispatcher, and registry.
- Keep blast radius low: edits stay in the working tree until the user explicitly commits; every write is path-safety validated; git operations are restricted to the same allow-list.
- Disabled by default. Existing deployments are unaffected unless `mcp.edit_enabled: true` is set.

## Non-goals

- Live DB writes (Slice 3 / 2.5).
- Live game-state mutations (Slice 3).
- WZ XML editing — out of scope; design says use HaRepacker for WZ work.
- Workflow features beyond diff/commit/revert (no rebase, push, merge, branch management).
- Multi-tenant authorization scopes — single bearer token continues to gate access (same as Slice 1).

## Key decisions (from brainstorm)

| Decision | Choice | Why |
|---|---|---|
| Writeable surfaces | (a) JS scripts, (b) `config.yaml`, (c) drop SQL files, (e) new script files. (d) live DB writes deferred. | Git-tracked surfaces share one design; live DB needs its own. |
| Edit flow | Hybrid — one-step write with optional `dry_run: true` for preview. | Default matches IDE-style edits; `dry_run` available when caller wants preview without filesystem change. |
| Edit input shape | Mix — find-replace OR full content. | Find-replace covers ~90% surgically; full content covers new files / large rewrites. |
| Tool decomposition | Per-surface + 3 git tools (6 total). | Per-surface enables embedded path validation and YAML parse check; git tools close the chat-only loop. |
| Format validation | YAML only on `config.edit`. | JS/SQL syntax validation has too many false positives; runtime/Liquibase catches errors. |
| Default state | `mcp.edit_enabled: false`. | Existing deployments are unaffected by upgrade. |

## Architecture

### Where it lives

New code lives under the existing `src/main/java/mcp/` package, which is already loaded in-process by Slice 1. The same `McpServer`, `HttpJsonRpcHandler`, `McpDispatcher`, and `ToolRegistry` host the new tools without changes.

### Lifecycle

`net.server.Server.init()` already builds the registry list. After Slice 2 lands, the same MCP boot block conditionally appends the 6 new tools when `mcpConfig.editEnabled()` is true:

```java
if (mcpConfig.editEnabled()) {
    Path repoRoot = Path.of(mcpConfig.repoRoot()).toAbsolutePath().normalize();
    mcpTools.add(new mcp.tools.ScriptEditTool(repoRoot));
    mcpTools.add(new mcp.tools.ConfigEditTool(repoRoot));
    mcpTools.add(new mcp.tools.DropsEditSqlTool(repoRoot));
    mcpTools.add(new mcp.tools.GitDiffTool(repoRoot));
    mcpTools.add(new mcp.tools.GitCommitTool(repoRoot));
    mcpTools.add(new mcp.tools.GitRevertTool(repoRoot));
}
```

### Components

```
src/main/java/mcp/
  edit/
    EditLock.java            // global ReentrantLock singleton
    PathSafety.java          // path validation against allow-list per surface
    DiffBuilder.java         // unified diff via java-diff-utils
    GitRunner.java           // ProcessBuilder wrapper around the `git` CLI
  tools/
    ScriptEditTool.java      // 14: cosmic.script.edit
    ConfigEditTool.java      // 15: cosmic.config.edit
    DropsEditSqlTool.java    // 16: cosmic.drops.edit_sql
    GitDiffTool.java         // 17: cosmic.git.diff
    GitCommitTool.java       // 18: cosmic.git.commit
    GitRevertTool.java       // 19: cosmic.git.revert

src/main/java/config/
  McpConfigYaml.java         // + edit_enabled, repo_root

src/main/java/mcp/config/
  McpConfig.java             // + editEnabled, repoRoot
```

### Repo root resolution

`McpConfig.repoRoot` defaults to `"."`. Resolved at MCP startup against Cosmic's working directory via `Path.of(repoRoot).toAbsolutePath().normalize()`. The standard Cosmic launch (IDE, `./mvnw`, `launch.bat`, `docker compose up`) starts the JVM with the repo root as the CWD, so the default works. A user running the fat JAR from `/opt/cosmic` would set `repo_root: "/path/to/repo"`.

### New runtime dependency

- `io.github.java-diff-utils:java-diff-utils:4.12` — small (~45 KB), no transitive deps. Used by `DiffBuilder`.

No new test dependency. Path-safety and tool tests use `@TempDir`; git tool tests use a temp git repo with the `git` CLI (skipped via JUnit `Assumption` if `git` is not on PATH — same pattern Slice 1 uses for Docker).

### Concurrency

A single `ReentrantLock` (`EditLock.INSTANCE`) serializes all six tools' top-level work. Read-only tools from Slice 1 are unaffected. Edit operations are short (single-file IO + small process exec), so contention does not matter in practice. Lock acquisition uses `tryLock(2, SECONDS)`; failure → `-32000 edit_busy`.

### Hot-reload note

Cosmic auto-reloads JS scripts on next NPC interaction. An edit to `scripts/npc/9201000.js` goes live the next time someone talks to that NPC. The MCP does **not** suspend reload; the user controls the server's lifecycle. The `dry_run: true` mode lets the user preview without writing if that matters for a given edit.

## Tool surface

All six tools are registered alongside Slice 1's tools when `mcp.edit_enabled: true`. Names follow `cosmic.<group>.<verb>` like Slice 1.

### 14. `cosmic.script.edit`

**Input** (XOR — exactly one of the two shapes):
- Find-replace: `{ path: string, old_string: string, new_string: string, replace_all?: boolean = false, dry_run?: boolean = false }`
- Full content: `{ path: string, content: string, dry_run?: boolean = false }`

**Path allow-list:** `path` must start with `scripts/`, end in `.js`, and not contain `..`. Resolved path must remain inside the repo root.

**Returns:** `{ path, mode: "applied" | "preview", diff, created: boolean }`

`created` is true if the file did not exist prior to the call (only possible in full-content mode; find-replace requires an existing file).

### 15. `cosmic.config.edit`

**Input** (XOR same as above).
**Path allow-list:** `path` must equal `config.yaml`.
**Format check:** post-edit content is parsed by `YAMLMapper.readTree`. On failure, the edit is rejected with `invalid YAML: <message>`; nothing is written.
**Returns:** `{ path, mode, diff, created: false }`. Creating `config.yaml` is impossible (the file always exists in a Cosmic checkout) — no `created` semantics here.

### 16. `cosmic.drops.edit_sql`

**Input** (XOR same as above).
**Path allow-list:** `path` is one of `src/main/resources/db/data/131-reactordrops-data.sql`, `src/main/resources/db/data/151-global-drop-data.sql`, or `src/main/resources/db/data/152-drop-data.sql`. (Other SQL files in that directory — shops, maker, monster cards — are not in scope; bundle them into a follow-up if needed.) `..` rejected.
**No syntax validation** — these files are bulk INSERTs that JSqlParser handles poorly; Liquibase will catch errors at next migration.
**Returns:** `{ path, mode, diff, created }`.

### 17. `cosmic.git.diff`

**Input:** `{ path?: string }`. If `path` is provided, it must satisfy *some* edit tool's allow-list (i.e., be a script, `config.yaml`, or a drops SQL file). Without a path, returns the diff for all uncommitted changes inside the union of the three allow-lists (NOT the entire repo).

**Returns:** `{ diff: string }`. Output is `git diff --` stdout. Empty string if there are no changes.

**Implementation:** When `path` is provided, `git diff --no-color -- <path>`. When omitted, `git diff --no-color -- scripts config.yaml src/main/resources/db/data/131-reactordrops-data.sql src/main/resources/db/data/151-global-drop-data.sql src/main/resources/db/data/152-drop-data.sql`. Run from `repoRoot`. Stderr captured; non-zero exit → `git diff failed: <stderr>`.

### 18. `cosmic.git.commit`

**Input:** `{ paths: string[], message: string }`.
- Each `paths[]` entry is path-safety validated against the union of the three edit allow-lists. Any rejected entry → INVALID_PARAMS, nothing staged.
- `message` is required, non-empty.

**Returns:** `{ sha: string, files_committed: string[] }`.

**Implementation (atomic):**
1. Validate every path against the allow-list. Refuse the call entirely if any path fails.
2. `git add <paths>` (no `-A`, only the listed paths).
3. `git commit -m <message>`.
4. `git rev-parse HEAD` to capture the new SHA.

If `git commit` fails (e.g., nothing staged, hook rejection), reset the index for the staged paths via `git reset HEAD <paths>` and return `INTERNAL_ERROR`. We never silently leave files staged.

The commit is not pushed. The user pushes from a terminal when ready.

### 19. `cosmic.git.revert`

**Input:** `{ path: string }`.
- `path` must satisfy one of the three edit allow-lists.

**Returns:** `{ path: string, reverted: true }`.

**Implementation:** `git checkout -- <path>` after path-safety check. The behavior is to discard uncommitted changes for that path (working tree reset to HEAD). It does NOT touch staged changes — if `git add` was run on the file, the user must reset the index separately. This is intentional minimal scope; we are not building a general undo system.

For new files (created by `cosmic.script.edit` with `content` against a non-existent path), `git checkout --` does nothing because the file is untracked. Document this: untracked files must be removed via filesystem or `git clean`. Slice 2 does NOT expose `git clean` — too dangerous.

## Configuration additions

Add to `config.yaml` (default values shown):

```yaml
mcp:
  ...existing Slice 1 keys...
  edit_enabled: false
  repo_root: "."
```

`McpConfigYaml` gains `public boolean edit_enabled;` and `public String repo_root;`.

`McpConfig.from()` reads them, defaulting `repo_root` to `"."` when null/blank. No length validation on `repo_root`; the path is verified to be a real directory at MCP startup; if not, the edit tools are not registered and a WARN is logged (server continues).

## Error handling

| Condition | Code | Message format |
|---|---|---|
| Both find-replace fields and `content` provided | -32602 | "provide either find-replace fields or content, not both" |
| Neither provided | -32602 | "provide either {old_string,new_string} or content" |
| `old_string` not found in target file | -32602 | "old_string not found in <path>" |
| `old_string` matches >1 without `replace_all=true` | -32602 | "old_string matches <N> times in <path>; pass replace_all=true or expand context" |
| Path outside allow-list, contains `..`, or escapes repo root | -32602 | "path not allowed: <path>" |
| Path is a symlink (after `toRealPath`) and target is outside repoRoot | -32602 | "path not allowed: <path>" |
| YAML parse failure on `config.edit` post-content | -32602 | "invalid YAML: <jackson message>" |
| Find-replace called on a path that does not exist | -32602 | "no such file: <path>" |
| `git.commit` called with empty paths array | -32602 | "paths must be a non-empty array" |
| `git.commit` called with empty/blank message | -32602 | "message must be non-empty" |
| Filesystem IO failure on write | -32603 | "write failed: <io message>" |
| `git` subprocess exits non-zero | -32603 | "git <cmd> failed: <stderr>" |
| Edit lock not acquired in 2s | -32000 | "edit_busy" |

Stack traces stay in the server log only; clients receive sanitized messages.

## Observability

Reuses Slice 1's existing log format. One additional INFO line per write:

```
mcp_edit tool=<name> path=<rel-path> mode=<applied|preview> caller=<ip>
```

`git.commit` logs at INFO with the new SHA. `git.revert` logs at WARN (revert is unusual enough to flag).

## Testing

### Unit tests (JUnit 5 + `@TempDir`)

- **`PathSafety`** — each surface's allow-list, rejecting `..`, symlinks to outside, non-`.js` extensions for scripts, paths outside data dir for drops SQL, paths != `config.yaml` for config.
- **`DiffBuilder`** — golden cases of single-line replace, multi-line replace, file creation, identical content (empty diff).
- **`ScriptEditTool` / `ConfigEditTool` / `DropsEditSqlTool`** — happy path for find-replace and full-content; both-provided rejection; not-found rejection; multi-match-without-replace_all rejection; dry-run returns diff without writing; full-content creates new file when path is absent (only the script and drops tools — config can't create).
- **`ConfigEditTool`** — invalid YAML rejection; verifies file unchanged after rejection.

### Git tool tests

Each test creates a real temp git repo via `git init` (using `ProcessBuilder` from the test). `Assumptions.assumeTrue("git" is on PATH)` gate at `@BeforeAll`. Tests cover:
- `GitDiffTool` — empty repo (empty diff); after writing a tracked file (non-empty diff); after staging (still non-empty until commit).
- `GitCommitTool` — single file commit returns the SHA; rejects paths outside allow-list; rejects empty message.
- `GitRevertTool` — reverts modified file; rejects path outside allow-list; no-op on untracked file (returns success but file is still there — test asserts current contract).

### Manual verification (Task 20 of the new plan)

- Edit `scripts/npc/<existing-id>.js` via Claude. Confirm the file changes. In a running Cosmic, talk to the NPC and confirm the new behavior takes effect.
- Edit `config.yaml` to flip a world rate. Restart Cosmic. Confirm the rate changed.
- Add a global drop via `src/main/resources/db/data/151-global-drop-data.sql`. Restart Cosmic. Confirm Liquibase applied the migration and the new drop appears in `cosmic.drop.search`.
- Run `cosmic.git.diff` and confirm output matches `git diff` from the terminal.
- Run `cosmic.git.commit` with two paths and a message; confirm the new SHA exists.
- Run `cosmic.git.revert` on a still-uncommitted edit; confirm the file reverts.
- Confirm path safety: try `cosmic.script.edit` with `path: "../etc/passwd"` and confirm `-32602`.
- Confirm YAML guard: try `cosmic.config.edit` with `content: "not valid: [yaml"` and confirm rejection without write.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Edit goes live before review (NPC hot-reload) | `dry_run: true` for sensitive edits; spec documents the behavior; users can stop the server during risky edits. |
| `git checkout --` discards user's manual unrelated changes | The path is validated to be within the allow-list; user changes to other paths (e.g., Java source) are unaffected by `cosmic.git.revert`. |
| Stale process: `git commit` runs while the user has an editor open with unsaved changes | Out of scope — this is a developer workflow issue, not an MCP correctness issue. The `git` CLI handles its own locking. |
| Path-safety bypass via TOCTOU (symlink swap) | `Path.toRealPath()` resolves at validation time. A swap after validation but before write is not credibly exploitable on a single-user LAN. |
| Format validation false-negative leaves bad YAML | Only the YAML parse check runs. If parser accepts but Cosmic config loader rejects, the user sees the error at next restart. Acceptable. |
| `edit_enabled: true` but `repo_root` is wrong | MCP boot block validates that `repo_root` exists and is a directory; logs WARN and skips edit-tool registration; read tools still work. |
| Concurrent commit by another process | `git` is concurrency-safe via its own index lock. Worst case: `git.commit` fails with stderr from git's lock; we propagate as `-32603`. |

## Out of scope

- WZ XML editing.
- Live DB writes (deferred — Slice 2.5 or 3).
- Live game-state mutations.
- New script categories (`scripts/portal/`, `scripts/event/` are already covered by the `scripts/**/*.js` allow-list, but adding new top-level script kinds requires no spec change — just a documentation update).
- Branch management (push, pull, rebase, merge, switch).
- Generic file edit. Per-surface tools intentionally chosen.

## Open questions for implementation

- Confirm `java-diff-utils` produces unified-diff output compatible with `git apply` (probably yes — it follows GNU diff format). If not, the diff is still informational only; we don't need to apply it.
- Confirm `git checkout -- <path>` behavior on Cosmic's git config, especially with hooks. If a pre-commit hook makes `git.commit` slow or interactive, document.
- Decide where to log the new `mcp_edit` INFO line. Reuse the existing log4j2 root config (no new appender).
