# Cosmic IRC Bridge — Design

**Date:** 2026-05-07
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Bidirectional bridge between a new in-game "world chat" surface and IRC channels on an external IRC network — one IRC channel per Cosmic world.

## Background

Cosmic chat today is partitioned by surface (map / whisper / party / guild / alliance / megaphone) and by world. There is no equivalent of a free-flowing world-wide chat short of consuming a Super Megaphone item. This spec adds a new "world chat" surface usable from in-game via `@world <text>`, and bridges that surface bidirectionally to IRC so chat stays in sync between the game and an external IRC network.

The bridge is a relay bot, not an embedded ircd: Cosmic dials out to a real IRC network (e.g. Libera) as a single connection, joins one channel per world, and ferries `PRIVMSG` lines in both directions.

## Goals

- Add a new `@world <text>` chat command that broadcasts world-wide to every online player in that world.
- Mirror world-chat traffic to a configured IRC channel per world; mirror inbound IRC traffic on those channels back to in-game players as a lightblue server notice.
- Default off. New `irc:` config block; bridge does not start unless `irc.enabled: true`.
- Game stays fully functional if the IRC connection is broken: outbound traffic is still delivered to in-game players via a self-loop, the IRC writer queue drops with a log when full, and reconnect is automatic.
- Anti-spam scaffolding: per-character token-bucket rate limit + length cap on `@world`.

## Non-goals

- **Per-guild / per-map / whisper bridging.** Only the new world-chat surface is bridged. Guild and party chat stay private to the game.
- **Cosmic acting as an IRCd.** Clients connect to a real IRC network, not to Cosmic.
- **Spawning a synthetic in-game character for IRC users.** v83's `CHATTEXT` packet renders the speaker name from a client-side roster lookup keyed by character id, so injecting an arbitrary "[IRC]nick" speaker through normal chat would require spawning a fake character on every player's map. IRC traffic is rendered in-game as a server notice (lightblue chat-log line) instead.
- **IRCv3 capabilities, SCRAM SASL, message tags, DCC, channel modes.** Only `NICK`, `USER`, optional `PASS` / SASL PLAIN, `JOIN`, `PRIVMSG`, `PING/PONG`, and `QUIT` are implemented. CTCP `ACTION` is rendered as `* nick text`; other CTCP is dropped.
- **Multiple IRC networks.** One Cosmic instance bridges to one IRC server.
- **Message persistence / replay.** Chat is ephemeral; missed traffic stays missed.
- **Hot reload of config.** Restart Cosmic to pick up changes, consistent with the `mcp:` config block today.
- **Linked accounts (IRC ↔ game).** IRC users render with a `[IRC]` prefix; in-game players render with their character name in IRC. No mutual identification beyond that.

## Key decisions (from brainstorm)

| Decision | Choice | Why |
|---|---|---|
| Direction | Bidirectional | User chose C in Q1. |
| Bridged surfaces | New `@world` chat surface only | User chose A in Q2. Map chat is too local; guild/party are intentionally private. |
| In-game rendering of IRC traffic | `serverNotice(6, "[IRC]nick: text")` (lightblue chat-log line) | User chose A after the constraint check on Q3 — `CHATTEXT` cannot inject arbitrary speaker names without spawning ghost characters. |
| In-game trigger | New `@world <text>` command, no permission gate | User chose A + G1 in Q4. Length cap and per-character rate limit added by the bridge maintainer to keep the open-gate decision safe. |
| IRC role | Cosmic-as-IRC-client | User chose A in Q5. Vastly simpler than embedding an ircd. |
| Channel layout | One IRC channel per world | User chose A in Q6. Matches the existing world-isolation invariant; players cannot `@world` across worlds today. |
| IRC client implementation | Hand-rolled minimal client | User chose A in the implementation-approach picker. ~250 LOC implementing the subset we need; no new transitive deps. |

## Architecture

### Where it lives

New code lives under `src/main/java/net/server/chat/irc/`. The new chat command lives under `src/main/java/client/command/commands/gm0/` (next to other `@`-commands) and is registered in `CommandsExecutor`. Outbound delivery to in-game players reuses `Server.broadcastMessage(world, packet)`. No changes to the network/login boot path.

### Lifecycle and registration gate

`net.server.Server.init()` reads `YamlConfig.config.irc` after the MCP boot block. If `irc.enabled: false`, nothing happens — the bridge is never instantiated, the `@world` command is not registered, no thread is started. If enabled but malformed (missing `server`, `nick`, or zero valid channels), a `WARN` is logged and the bridge skips startup; server boot is not blocked.

```java
IrcConfig ircConfig = IrcConfig.from(YamlConfig.config.irc);
if (ircConfig.enabled()) {
    if (!ircConfig.isValid()) {
        log.warn("IRC bridge enabled but config is invalid: {}", ircConfig.validationError());
    } else {
        IrcBridgeService.start(ircConfig);  // installs @world command on success
    }
}
```

`Server.shutdown()` calls `IrcBridgeService.stop()`; the bridge sends `QUIT :Cosmic shutting down`, drains its writer queue with a 2s timeout, closes the socket, and joins both threads.

### Components

| Class | Role |
|---|---|
| `IrcBridgeService` | Process-wide singleton owning the connection. `start(config)` registers the `@world` command and spins up `IrcConnection`. `stop()` is idempotent and bounded by a 2s shutdown deadline. |
| `IrcConnection` | One TCP `Socket` (or `SSLSocket` if `tls: true`). One read thread parses incoming lines; one writer thread drains a bounded `LinkedBlockingQueue<String>`. Reconnects with capped exponential backoff on `IOException`. Tracks current bot nick (server may rewrite it on collision via `433`). |
| `IrcLineParser` | Stateless. Parses `:prefix COMMAND param... :trailing` into a typed record. Pure unit-testable. |
| `IrcConfig` | Typed wrapper over `YamlConfig.config.irc`. Validates on construction; `isValid()` / `validationError()` for the boot-time check. |
| `WorldChannelMap` | Two-way bidirectional map: `worldId ↔ IRC channel name`. Built from `irc.channels` config. Channel names are normalized lowercase for IRC-side lookups (IRC channels are case-insensitive in the protocol). |
| `WorldChatService` | The two-direction surface. `send(worldId, charName, text)` for game→IRC; `deliverFromIrc(worldId, nick, text)` for IRC→game. Both fan out — `send` also self-loops the message to in-game players in the same world (since `@world` has no other broadcast path). |
| `WorldChatCommand` | New `@world <text>` command, registered as a gm0 (player-accessible) command. Applies length cap, calls `RateLimiter.tryAcquire(charId)`, and forwards to `WorldChatService.send`. |
| `RateLimiter` | Per-character token bucket. Tokens regenerated based on a parameterized `Clock` so tests can control time deterministically. |

### Threading and back-pressure

- Read thread: blocks on `BufferedReader.readLine()`; one line → one parse → one dispatch → one in-game broadcast (game broadcasts are fire-and-forget — `Server.broadcastMessage` already serializes the packet and drops onto each connected client's outbound queue).
- Writer thread: blocks on `queue.take()`; one line → one socket write + flush.
- Game-thread callers of `WorldChatService.send` enqueue with `queue.offer(line)` and **never block on the socket**. If `queue.size() >= outbound_queue_max`, the message is dropped and a throttled `WARN` (≤1/sec) is logged. The game-side self-loop still happens regardless — players in-world always see each other's `@world` chat even if IRC is offline.
- Reconnect: on `IOException`, the read thread closes the socket, sleeps the next backoff value (`reconnect_backoff_seconds[i]`, advancing through the array, capping at the last value), and re-runs the registration sequence (`PASS` → `NICK` → `USER` → `JOIN`s). The writer thread observes the socket-closed signal, completes the in-flight write or aborts on exception, and waits for a fresh socket.

### Data flow

#### Outbound: game → IRC

```
Player types "@world hello"
  → GeneralChatHandler delegates to CommandsExecutor
  → WorldChatCommand.execute(player, "hello")
      · text = trim/strip-controls/truncate to worldchat_max_length
      · if text.isEmpty(): return silently
      · if !rateLimiter.tryAcquire(player.id()): return silently
      · WorldChatService.send(player.world(), player.name(), text)
  → WorldChatService.send(worldId, name, text):
      · localBroadcast: Server.broadcastMessage(worldId,
            PacketCreator.serverNotice(6, name + ": " + text))
      · ircChannel = WorldChannelMap.channel(worldId)  // null if not bridged
      · if ircChannel != null AND IrcConnection.isConnected():
          IrcConnection.enqueue("PRIVMSG " + ircChannel + " :" + name + " " + text)
  → Writer thread eventually writes to socket; flush; loop.
```

#### Inbound: IRC → game

```
Read thread reads a line; IrcLineParser.parse(line)
  → if PING <token>: writer queue gets "PONG <token>"
  → if 433 (nick in use): append "_" to nick, retry NICK
  → if PRIVMSG <chan> :<text>:
      · nick = sender.nickFromPrefix(line.prefix)  // "nick!user@host"
      · if nick.equalsIgnoreCase(currentBotNick): drop (echo-loop guard)
      · text = sanitize(text):
          · CTCP ACTION ("\x01ACTION ...\x01") → "* nick text"
          · other CTCP (\x01...\x01) → drop
          · strip control chars \x00-\x1F (preserve \x20+)
          · truncate to worldchat_max_length
      · worldId = WorldChannelMap.world(chan.toLowerCase())
      · if worldId is null: drop (joined channel no longer mapped)
      · WorldChatService.deliverFromIrc(worldId, nick, text)
  → deliverFromIrc(worldId, nick, text):
      · Server.broadcastMessage(worldId,
            PacketCreator.serverNotice(6, "[IRC]" + nick + ": " + text))
```

### Configuration

New `irc:` block in `config.yaml`:

```yaml
irc:
  enabled: false
  server: irc.libera.chat
  port: 6697
  tls: true
  nick: cosmic-bridge
  user: cosmic
  realname: Cosmic Chat Bridge
  password: ""              # optional; sent via SASL PLAIN if set and TLS is true
  allow_plaintext_password: false
  channels:                 # worldId → IRC channel
    0: "#cosmic-scania"
    1: "#cosmic-bera"
    2: "#cosmic-broa"
  outbound_queue_max: 1000
  worldchat_rate_per_minute: 6
  worldchat_max_length: 200
  reconnect_backoff_seconds: [5, 10, 30, 60, 60]
```

Validation rules (applied in `IrcConfig.from`):
- `enabled=true` requires non-empty `server`, `nick`, and at least one entry in `channels`.
- `port` must be in `[1, 65535]`.
- If `password != ""` and `tls=false`, require `allow_plaintext_password: true`. Otherwise reject.
- Unknown world ids in `channels` are dropped with a `WARN` and the bridge starts with the remainder.

### Error handling and failure modes

| Failure | Behavior |
|---|---|
| IRC server unreachable / drops | `IOException` → log `WARN` → reconnect with capped backoff. Game traffic continues to self-loop in-world. |
| Writer queue full | `enqueue` is a non-blocking `offer`; if it returns false, drop the message; one throttled `WARN` per second to avoid log spam. |
| Malformed inbound line | Parser logs `DEBUG` and skips. Never propagates exceptions to the read loop's `try/catch`. |
| Inbound text contains control chars | Stripped (`\x00-\x1F` except space) before broadcast. CTCP `ACTION` rendered as `* nick text`; other CTCP dropped. |
| Inbound text too long | Truncated to `worldchat_max_length` chars + `…`. |
| `@world` rate-limited | Silent drop; no in-game error notice, no IRC notice. |
| Bot kicked / channel-banned | Rejoin attempt at the next backoff tick. After 5 consecutive rejoin failures for the same channel, the channel is disabled until restart and `WARN` is logged. |
| Echo loop (own message back) | Inbound nick compared case-insensitively against current bot nick → drop. |
| Cosmic shutdown | `IrcBridgeService.stop()` sends `QUIT :Cosmic shutting down`, drains the writer queue with a 2s timeout, closes the socket, joins both threads. |
| Bridge thread crash | Top-level `try/catch` in both threads logs at `ERROR` with stacktrace and triggers reconnect. Threads never silently die. |

### Testing strategy

| Layer | Test approach |
|---|---|
| `IrcLineParser` | Pure unit tests with crafted strings: `:nick!u@h PRIVMSG #chan :hi`, `PING :foo`, numerics (`:server 001 cosmic-bridge :Welcome`), malformed lines, CTCP wrapping, control-char stripping. No socket needed. |
| `IrcConnection` socket I/O | A `FakeIrcServer` test harness (`ServerSocket` on `127.0.0.1:0`) the test starts in-process. Drives `IrcConnection` against it: assert `NICK`/`USER`/`JOIN` sent on connect; inject lines, assert callbacks fire; close socket, assert reconnect happens. Backoff is parameterized so tests pass `[10, 20]` ms instead of seconds. |
| `WorldChatService.send` | Unit test with a fake `IrcConnection` (captures enqueued lines) and a fake `Server` broadcast (captures local-broadcast packets). Assert both fan-outs happen. |
| `WorldChatService.deliverFromIrc` | Same fake-Server setup. Assert correct `serverNotice` packet built; assert echo-loop drop when nick matches bot's nick. |
| `WorldChatCommand` | Unit test with a fake service. Assert: rate-limit drops the second message inside the bucket window; over-length text is truncated; empty / whitespace-only `@world` is rejected. |
| `RateLimiter` | Pure unit test with a controllable `Clock`. |
| `IrcConfig` | Unit test parses representative YAML snippets, asserts validation errors for missing `server`, invalid channels, plaintext-password without `allow_plaintext_password`, etc. |
| `IrcBridgeService` lifecycle | Integration test against `FakeIrcServer`: `start()`, send `@world` via `WorldChatCommand`, assert `PRIVMSG` appears on the fake server. Inject inbound `PRIVMSG`, assert local-broadcast captured. `stop()` triggers `QUIT` and clean shutdown within 2s. |

Out of scope for tests:
- Real IRC network — CI does not dial out.
- TLS handshake — relies on `SSLSocket` + standard JDK truststore. Manual smoke test against the real network is the verification.
- Threading stress — the structure is two fixed threads with one bounded queue. Concurrency review is by code review, not load tests.

Coverage target: every new class has a unit test class. Existing project test style (hand-rolled fakes, no Mockito) is followed.

## Operational notes

- **Default state:** `irc.enabled: false`. Existing deployments are unaffected by upgrade.
- **TLS:** required by default (`tls: true`, port 6697). Plaintext is supported for self-hosted internal ircds but plaintext passwords require an explicit second flag.
- **Logging:** all bridge events go through SLF4J under `net.server.chat.irc` so they can be filtered/levelled separately from gameplay logs.
- **Privacy:** world chat is now globally observable (the IRC channels are publicly joinable on the external network). Document this in the README. Players who do not want their character name on a public IRC log should not type `@world`. The same warning applies to the existing super-megaphone, but it's worth restating.

## Open questions deferred to plan

- Exact `gm0` registration boilerplate (look up `commands/gm0/HelpCommand.java` or similar pattern).
- Whether to surface `IrcConnection.isConnected()` to the player (e.g. `@world` returns "(IRC offline; in-game only)" notice). Default: silent — the design specifies silent drops.
