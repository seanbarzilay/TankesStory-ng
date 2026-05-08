# Player Bot — Design

**Status:** Draft
**Date:** 2026-05-08
**Scope:** v1 — in-process player bot that acts as a recruitable companion (mode `FOLLOW`) and as a standalone grinder (mode `GRIND`). No chat, no DB persistence.

## Goals

- Provide a server-side "fake player" that other real clients see as a normal `Character` on the map.
- Two driving modes for v1: `FOLLOW` a real player, and `GRIND` mobs in a radius.
- Two control surfaces: in-game `@bot` commands for GMs, and MCP tools for external automation. Both gated by config.
- Reuse existing `Character` / `MapleMap` / combat / drop / portal infrastructure. Do not introduce a parallel lifecycle.

## Non-goals (v1)

- No chat (general / party / whisper) — bots are silent.
- No DB persistence — bots are ephemeral and disappear on server restart.
- No skills (`B2`) — basic auto-attack only.
- No real packet-protocol client / load-test harness — the bot lives in-process.
- No protocol-level testing — the bot bypasses login/auth.
- No undo of admin spawn/drive operations beyond what `mcp_admin_audit` records.

## MVP feature list

| ID | Feature |
|----|---------|
| A1 | Bot is visible to other players as a normal `Character` |
| A2 | Walk / jump toward a target (x, y) on the current map |
| A3 | Change maps via portals |
| B1 | Auto-attack nearby mobs (basic melee/ranged, no skills) |
| B3 | Take damage; use HP/MP pots from inventory; die and revive |
| B4 | Pick up dropped `MapItem`s when inventory has space |
| C1 | Join / leave a party with a real player (cannot lead) |
| C2 | Follow a real player (`FOLLOW` mode) |
| D2 | Ephemeral, in-memory only — no row in `characters` |

## Architecture

A new `client.bot` + `server.bot` pair of packages built on the existing in-process `Character` / `MapleMap` infrastructure.

A bot is a real `Character` instance attached to a `BotClient` — a subclass of `Client` whose `sendPacket(Packet)` is a no-op and whose lifecycle hooks (`disconnect`, remote-address methods) are stubbed. The bot is added to a `MapleMap` via the existing `MapleMap.addPlayer(Character)` path, so real players nearby receive normal `spawnPlayer` / `movePlayer` / attack broadcasts and see it as just another character.

A single shared `BotScheduler` ticks every ~200 ms (configurable). Each live bot has a `BotBrain` that runs one tick: it reads bot state + map state, decides a single action, and invokes the corresponding existing server method.

### Why this shape

- Bot is indistinguishable to other players because it goes through the same broadcast paths real movement and combat already use.
- One brain per tick = simple, debuggable, bounded; no thread-per-bot.
- Stays inside the existing `Channel` / `MapleMap` mental model — no parallel lifecycle.
- Matches the in-process design pattern already established by the MCP admin tooling (Slice 3).

## Components

### `BotClient extends Client` — `client.bot.BotClient`

- Overrides `sendPacket(Packet)` → no-op.
- Overrides `disconnect(...)` → routes to `BotManager.despawn(this)`.
- Overrides `getRemoteAddress()` → returns `"bot"` for logging / online-list filtering.
- Constructed without a Netty `Channel`. We must audit `Client` for any direct `ioChannel` access (`sendPacket` body confirmed; others to verify) and either guard or override.
- A small number of `Client` methods may need their visibility relaxed from `private` to `protected` so `BotClient` can override cleanly. The implementation plan must enumerate exactly which.

### `BotFactory` — `client.bot.BotFactory`

- Builds an in-memory `Character` (no DB row) with:
  - synthetic negative `id` starting at `-1_000_000` and decrementing
  - name from a pool: `<name_prefix><NN>` (default `Bot01`, `Bot02`, ...)
  - starting job / level / HP / MP / equips from a small preset table (begin with one preset: `Beginner Lv 30`)
  - parent = `BotClient`
- `spawn(world, channel, mapId, x, y, preset?) → Bot` puts the bot into the target `MapleMap` via `addPlayer`.
- `despawn(bot)` removes the bot via `removePlayer` and unregisters from `BotManager`.

### `Bot` — `server.bot.Bot`

- Wraps a `Character` + `BotBrain` + per-bot config: `mode`, `targetCharId`, `mobFilter`, pot thresholds.
- Mutable mode enum: `IDLE`, `FOLLOW`, `GRIND`.

### `BotBrain` interface and `DefaultBotBrain`

- `tick(Bot, MapleMap, long now)` picks one action per tick.
- Decision priority (top wins):
  1. **Survival** — HP% below threshold → `USE_HP_POT` if pot in inventory; same for MP. If HP low and no pot → `RETREAT` toward nearest portal.
  2. **Dead** — schedule revive after `revive_delay_ms`.
  3. **Pending party invite** — if `bot.config.auto_accept_party` is true and the bot has a pending party invite, accept it via the existing party-accept server path. Default `auto_accept_party = true`.
  4. **Follow** (mode == `FOLLOW`) — if target on different map: walk to nearest portal whose `targetMap == target.mapId`, then `Character.changeMap(portal)`. If same map: step toward target until within `follow_radius`.
  5. **Grind** (mode == `GRIND`) — pick nearest live `Monster` in `grind_radius` matching `mobFilter`, walk into attack range, attack. Attack dispatch: if the bot's equipped weapon is ranged (bow/crossbow/claw/gun), emit `PacketCreator.rangedAttack(...)` and damage; otherwise emit `PacketCreator.closeRangeAttack(...)`.
  6. **Loot** — if a `MapItem` is in pickup radius and inventory has space, walk to it and `pickItemDrop`.
  7. **Idle** — no action.

### `BotScheduler` — `server.bot.BotScheduler`

- One `TimerManager` repeating task at `bots.tick_ms` (default 200 ms).
- Iterates a snapshot of `BotManager.activeBots()` and calls `brain.tick(...)` per bot.
- Per-tick `try { ... } catch (Throwable t) { log.warn(...) }` so one bot can't kill the loop.
- Three consecutive tick failures for the same bot → auto-despawn.

### `BotManager` — `server.bot.BotManager` (singleton)

- `ConcurrentHashMap` registry keyed by synthetic char id; world / channel scoped lookup.
- Lifecycle: `register`, `unregister`, `findByName`, `listInWorld`, `despawnAll`.
- Started from `Server.init` after worlds / channels exist, behind `bots.enabled` (default `false`).
- `despawnAll()` runs on server shutdown before channels close so `removePlayer` broadcasts are clean.

### `BotCommand` — `client.command.commands.gm1.BotCommand`

- Subcommands:
  - `@bot spawn [preset]` — spawn at the GM's position
  - `@bot follow <playername>` — set this GM's bot to FOLLOW the given player (defaults to nearest spawned bot if multiple)
  - `@bot grind [<mobIdOrLevelRange>]` — set GRIND mode with optional filter
  - `@bot stop` — set IDLE
  - `@bot despawn` — remove
  - `@bot list` — list active bots in the current world
- GM tier: `gm1` matches the existing convention for non-destructive admin tools; the implementation plan should confirm by checking whether comparable commands (e.g., NPC summon) live in `gm1` or higher.

### MCP tools — `mcp.tools` (gated by `mcp.admin_enabled`)

- `cosmic.bot.spawn(world, channel, map, x, y, preset?)` → returns synthetic char id
- `cosmic.bot.drive(bot_id, mode, target?, params?)` → sets mode / target
- `cosmic.bot.list(world?)` → returns active bots and their state
- All three audited via the existing `mcp_admin_audit` table; `before_json` captures bot mode / position before mutation. `cosmic.bot.spawn` records the spawn coordinates and id; `cosmic.bot.list` is read-only and not audited.

### Config — `config.yaml`

```yaml
bots:
  enabled: false
  tick_ms: 200
  max_per_world: 50
  hp_pot_item_id: 2000000
  mp_pot_item_id: 2000003
  hp_pct_threshold: 50
  mp_pct_threshold: 30
  follow_radius: 100
  grind_radius: 800
  revive_delay_ms: 3000
  name_prefix: "Bot"
  auto_accept_party: true
```

## Data flow

### Spawn (`@bot spawn` or `cosmic.bot.spawn`)

1. Caller invokes `BotFactory.spawn(world, channel, mapId, x, y, preset?)`.
2. Factory builds `BotClient` (no Netty channel), then a `Character` (synthetic negative id, preset stats / equips).
3. Factory calls `MapleMap.addPlayer(bot.character)` — existing path broadcasts `spawnPlayer` to nearby real clients.
4. Factory registers bot with `BotManager`; mode defaults to `IDLE`.

### Tick (every `bots.tick_ms` per bot)

1. `BotScheduler` invokes `brain.tick(bot, map, now)`.
2. Brain reads bot.HP%, position, mode, target state, nearby mobs / items.
3. Brain emits one action; the action is performed by calling existing server methods:
   - **Move step** → update position; `map.broadcastMessage(bot.character, PacketCreator.movePlayer(...), false)` (bot itself excluded since it has no view).
   - **Attack** → reuse existing damage / attack path; `map.broadcastMessage(bot.character, PacketCreator.closeRangeAttack(...) or rangedAttack(...))` then apply damage to mob via `map.damageMonster`.
   - **Pickup** → `map.pickItemDrop(...)` with bot.character as picker.
   - **Pot use** → consume from bot.character inventory and apply heal stat the same way `UseItemHandler` does.

### Follow (mode == `FOLLOW`)

- Each tick, look up target via `Server.getInstance().getCharacterFromAllServers(...)`.
- If target on different map: walk to nearest portal whose `targetMap == target.mapId`, then `Character.changeMap(portal)` (existing map-switch broadcasts handle the leave/enter).
- If same map: step toward target until within `follow_radius`.

### Grind (mode == `GRIND`)

- Pick nearest live `Monster` in `grind_radius` matching the optional level / id filter.
- Walk into attack range, attack, repeat. When a mob dies, the existing drop pipeline creates `MapItem` drops, which the loot decision picks up next tick.

### Party invite (player invites bot)

- Player runs the normal party-invite flow targeting the bot's name. The existing server-side party logic queues the invite as it does for any player.
- Bot's no-op `sendPacket` swallows the invite-prompt packet, so the client-side prompt never shows.
- On the bot's next tick, brain rule 3 detects the pending invite (read from existing party state) and calls the same accept path the client would call. Bot is now in the party. The player sees the standard "joined" broadcast.
- The implementation plan must verify how pending invites are exposed in the existing party data model (likely `World` or `PartyProcessor`); if they're only stored client-side, an alternate hook is needed (e.g., intercepting in the invite handler before the packet is sent to the bot).

### Death and revive

- When bot HP hits 0, the existing damage path triggers the death broadcast.
- Brain notices `!alive`, schedules a `revive_delay_ms` timer that resets HP / MP and re-broadcasts spawn.

### Despawn

- `@bot despawn` / `cosmic.bot.drive` despawn / `BotManager.despawnAll` (on server shutdown) → `MapleMap.removePlayer` (existing broadcast) → `BotManager.unregister` → `BotClient` references released.

### Concurrency model

- One scheduler thread runs all bot ticks sequentially. Brain logic does not acquire new locks.
- `BotManager` registry is a `ConcurrentHashMap`; the scheduler iterates a snapshot copy.
- Existing `Character` mutations (HP changes, inventory) already synchronize the way real players do; the bot does not change that.

## Error handling and edge cases

### Bot-internal errors

- Each `brain.tick()` is wrapped in try/catch.
- Three consecutive tick failures for a bot → auto-despawn (logged at WARN).

### Map / channel transitions

- `FOLLOW` target logged out → drop to `IDLE`, log INFO.
- Target moved to a map the bot can't enter (instanced, level-locked, expedition-only) → fall back to nearest reachable portal; if none, `IDLE`.
- `FieldLimit.CANNOTMIGRATE` and similar checks are respected — bot does not bypass them.

### Resource exhaustion

- Out of HP/MP pots and HP% below threshold → bot stops attacking and walks toward last known portal (best-effort retreat). If it dies, normal revive flow applies.
- Inventory full → skip pickups silently (DEBUG log only).

### Server lifecycle

- `BotManager.despawnAll()` runs on server shutdown before channels close so `removePlayer` broadcasts are clean.
- Bots are not persisted (D2). Restart wipes them. This is intentional.

### Concurrency hazards

- All brain-driven writes happen on the scheduler thread.
- HP changes from other paths (player attacks the bot, MCP admin-edit) go through `Character` methods that already synchronize.

### Config and safety guards

- `bots.enabled: false` → scheduler does not start; commands and MCP tools return "bots disabled".
- `bots.max_per_world` cap: spawn beyond cap returns an error.
- Synthetic id range (`-1_000_000` and below) is reserved. At server boot, verify no real `characters.id` is in that range; abort startup with a clear error if it is.

### Other edge cases

- Bot in a party → it can join but cannot lead. The bot auto-accepts pending party invites in its tick (brain rule 3). Leadership transfer is rejected at the bot side: if the existing party path tries to make a bot the leader (e.g., the human leader leaves), the bot leaves the party rather than accept leadership.
- Player whispers a bot → bot's no-op `sendPacket` swallows the whisper. We add a `WhisperFilter` to suppress the "user is offline" system message; the implementation plan must verify the existing whisper handler before fixing the exact mechanism.
- Bot kills a mob → existing drop pipeline runs normally. Bot's "ownership" of the kill is fine because there is no DB row to update.

## Testing

This codebase has no JDK locally; tests run via `podman run maven:3.9.6-amazoncorretto-21`. CI is dormant; the local podman run is the source of truth.

### Unit tests

- `BotClientTest` — `sendPacket` is a no-op; `disconnect` routes to `BotManager.despawn`; no Netty interactions.
- `BotFactoryTest` — synthetic id allocation is monotonic and stays in the reserved negative range; spawn places bot in target map and registers with `BotManager`; `bots.enabled: false` makes spawn return a disabled error.
- `DefaultBotBrainTest` — table-driven decision tests using a fake `Bot` + minimal map fixture:
  - low HP + has pot → `USE_HP_POT`
  - low HP + no pot → `RETREAT`
  - dead → `WAIT_REVIVE`
  - FOLLOW + target same map within radius → `IDLE`
  - FOLLOW + target same map outside radius → `STEP_TOWARD_TARGET`
  - FOLLOW + target on other map → `WALK_TO_PORTAL`
  - GRIND + mob in radius + melee weapon → `STEP_TOWARD_MOB` then `ATTACK_MELEE`
  - GRIND + mob in radius + ranged weapon → `STEP_TOWARD_MOB` then `ATTACK_RANGED`
  - GRIND + no mob → `IDLE`
  - pending party invite + auto_accept_party=true → `ACCEPT_PARTY_INVITE`
  - pending party invite + auto_accept_party=false → ignored
  - loot in pickup radius and inventory has space → `PICKUP`
  - loot in pickup radius but inventory full → skip
- `BotSchedulerTest` — three consecutive throws auto-despawn the offending bot; other bots continue ticking; `despawnAll` stops the loop cleanly.
- `BotManagerTest` — registry lookup by id and name, `max_per_world` cap, world / channel scoping.

### Integration tests

- Spawn a bot, walk one step, assert `MapleMap.getPlayers()` contains it and a movement packet was emitted to a recording observer.
- `@bot follow <player>` → simulate target step → bot tick produces a step in the target's direction.
- MCP `cosmic.bot.spawn` writes an audit row; `cosmic.bot.list` reflects the spawn.

### Manual smoke (documented, not automated)

- Boot server with `bots.enabled: true` and one configured channel.
- `@bot spawn` on a real GM character; verify the bot is visible to a second client.
- `@bot follow <other player>`, `@bot grind`, `@bot despawn` round-trip.

### Build verification

- `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test`
- All new tests run there.

### Out of scope for v1 testing

- No load test (no real protocol client).
- No long-running soak test — follow-up if v1 lands.

## Open items for the implementation plan

- Enumerate exactly which `Client` methods need visibility relaxed (and verify `ioChannel` is the only Netty touch point that `BotClient` must avoid).
- Confirm the GM tier directory (`gm1` vs higher) for `BotCommand`.
- Verify the existing whisper handler so the `WhisperFilter` design is correct.
- Confirm the bot preset table (start with a single `Beginner Lv 30` preset; expand later).
- Verify how pending party invites are stored / exposed in the existing party data model so the brain can detect and accept them; pick an interception point if invites are not server-side queryable.
