# Cosmic Telegram Bridge — Design

**Date:** 2026-05-07
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Replace the IRC bridge wholesale with a Telegram bridge: a bidirectional relay between the in-game `@world` chat surface and one Telegram group per Cosmic world, using the Telegram Bot API (long polling) and `com.pengrad:java-telegram-bot-api`.
**Replaces:** `2026-05-07-irc-bridge-design.md`. The IRC implementation and its supporting `ircd` compose service are removed in this slice.

## Background

The IRC bridge (PR #7 + follow-ups) added a new in-game `@world` surface with bidirectional relay to an external IRC network, gated on `irc.enabled`. The chat surface, rate-limit, length-cap, and self-loop semantics work well; the transport (a hand-rolled IRC client + a self-hosted `ircd` compose service) is what we want to swap out. Telegram is friendlier for staff/players to use day-to-day, doesn't require a separate self-hosted daemon, and lets us drop the `ircd` container entirely.

This spec replaces the IRC transport in place. The protocol-agnostic pieces (`WorldChannelMap`, `WorldChatService`, `WorldCommand`, `RateLimiter`, the `Server.init()` boot-block shape) carry over with minor type changes.

## Goals

- Relay `@world` traffic in both directions between Cosmic worlds and one Telegram group per world.
- Default off. New `telegram:` config block; bridge does not start unless `telegram.enabled: true` and a non-empty `bot_token` + `chats` map are configured.
- Game stays fully functional if Telegram is broken: outbound traffic is still delivered to in-game players via a self-loop, send failures are logged and dropped, polling failures retry with backoff.
- Anti-spam scaffolding: per-character token-bucket rate limit + length cap on `@world` (same defaults as IRC).
- Remove the IRC bridge code, the `ircd` compose service, and the `irc:` config block in the same change.

## Non-goals

- **Webhook delivery.** Long polling only (`getUpdates`).
- **MTProto / user accounts.** Bot API only.
- **Multiple bots / multiple Telegram networks.**
- **Persistence of `last_update_id`.** In-memory for the process lifetime; restart relies on Telegram's 24h server-side queue.
- **Re-trying a failed `sendMessage`.** Drop on first failure.
- **Edits, deletes, replies, reactions, inline buttons, voice chats, polls, photos, stickers, system messages.** Non-text updates are silently dropped on inbound; outbound is plain text only.
- **Linked accounts (Telegram user ↔ in-game character).** Players appear in TG as the bot's voice prefixed by their character name; TG users appear in-game as `[TG]<display>: text` with no auth between sides.
- **Hot reload of config.** Restart Cosmic to pick up changes.
- **Bot privacy-mode auto-detection.** Documented setup step, not runtime-detected.

## Key decisions (from brainstorm)

| Decision | Choice | Why |
|---|---|---|
| Replace vs add vs generalize | **Replace** the IRC bridge entirely | User chose A in Q1. Matches "switch X with Y"; removes the now-unused `ircd` container; the protocol-agnostic pieces still get reused. |
| API surface | **Bot API + long polling** | Default assumption. Smallest operational surface, no webhook server, no MTProto phone number. |
| Chat layout | **One Telegram group per Cosmic world** | User chose A in Q2. Mirrors the IRC layout 1:1; preserves world isolation. |
| Library | **`com.pengrad:java-telegram-bot-api`** | User chose A in the library picker. Lightweight (~1 MB), no Spring dependency, sync API maps cleanly onto our threading model. |
| Threading | One polling thread; outbound `sendMessage` is async (OkHttp pool) | No outbound queue needed — async callback handles success/failure; if TG is unreachable, the call fails fast and the in-game self-loop has already delivered the message. |
| Inbound non-text | Silently dropped | YAGNI for v1; placeholder rendering (e.g. `<photo>`) can be added later. |
| Echo loop | Not a concern | Telegram's `getUpdates` doesn't redeliver our bot's own messages. We keep `currentBotUsername()` for parity but the inbound path doesn't filter on it. |

## Architecture

### Where it lives

New code lives under `src/main/java/net/server/chat/telegram/`. The existing `net/server/chat/irc/` directory and its tests are deleted in this slice. The shared in-game pieces — `WorldChatService`, `WorldChannelMap`, `WorldBroadcaster`, `RateLimiter`, `WorldCommand` — move from `irc` into `telegram` with type narrowing where the channel-id type changes (`String` → `Long`). `Server.init()`'s boot-block shape is identical to today's, just instantiating `TelegramBridgeService` instead of `IrcBridgeService`.

### Lifecycle and registration gate

`Server.init()` reads `YamlConfig.config.telegram` after the MCP boot block. If `telegram.enabled: false`, nothing happens — the bridge is never instantiated, the `@world` command is not registered, no thread is started. If enabled but malformed (missing `bot_token`, empty `chats`, or invalid clamps), a `WARN` is logged and the bridge skips startup; server boot is not blocked. `Server.shutdownInternal()` calls `TelegramBridgeService.instance().ifPresent(b -> b.stop(2000))` immediately before the existing MCP shutdown.

### Components

| Class | Role |
|---|---|
| `TelegramBridgeService` | Process-wide singleton owning the `TelegramClient`. `start(config, broadcaster, clock)` registers `@world` and spins up the client. `stop(timeoutMs)` is idempotent and bounded. |
| `TelegramClient` | Wraps pengrad's `TelegramBot`. One **polling thread** runs `bot.execute(new GetUpdates().offset(N).timeout(...))` in a loop and dispatches accepted messages to a `Consumer<TelegramInbound>`. Outbound `sendToChat(chatId, text)` calls `bot.execute(SendMessage, callback)` async — no separate writer thread. |
| `TelegramConfig` | Typed wrapper over `YamlConfig.config.telegram`. Validates on construction; `isValid()` / `validationError()` for the boot-time check. Coerces yamlbeans's String-keyed `chats` map to `Map<Integer, Long>`. |
| `WorldChannelMap` *(retained, repackaged)* | Bidirectional `Map<Integer, Long> ↔ Map<Long, Integer>` (worldId ↔ Telegram chat_id). Replaces the `String`-valued IRC version. |
| `WorldChatService` *(retained, repackaged)* | Two-direction surface. `send(worldId, charName, text)` for game→TG; `deliverFromTelegram(worldId, sender, text)` for TG→game. Sanitize + truncate are unchanged. |
| `TelegramSender` | Interface used by `WorldChatService` to decouple from `TelegramClient`: `sendToChat(long chatId, String text)` + `currentBotUsername()` (kept for parity with `IrcSender`; unused on the inbound dispatch path because TG doesn't echo our own messages). |
| `WorldBroadcaster` *(retained)* | `(worldId, packet) -> void` — `Server::broadcastMessage` in production. |
| `WorldCommand` *(retained)* | `@world` command, gm0, original-case payload via `getLastCommandMessage()`, rate-limit + length-cap routing. Unchanged from IRC. |
| `RateLimiter` *(retained)* | Per-character token bucket with injectable `Clock`. Unchanged. |

### Threading and back-pressure

- **Polling thread.** Blocks on `bot.execute(getUpdates(timeout=25s))`. For each accepted text update, calls `WorldChatService.deliverFromTelegram(...)` synchronously. After the batch, advances `last_update_id` and loops.
- **Outbound.** `TelegramClient.sendToChat(...)` calls `bot.execute(SendMessage, Callback)` and returns immediately. OkHttp's connection pool handles concurrency; failures land in the callback (one throttled `WARN` per minute, plus the per-chat 5-failure mute).
- **No outbound queue.** Game-thread `@world` callers never block on the network: the in-game self-loop happens first, the async TG send happens after.

### Data flow

#### Outbound: game → Telegram

```
Player types "@world hello"
  → CommandsExecutor → WorldCommand.execute (UNCHANGED)
      · text = player.getLastCommandMessage().strip()  (preserves casing)
      · if text.isEmpty(): return silently
      · if !rateLimiter.tryAcquire(charId): return silently
      · WorldChatService.send(worldId, charName, text)
  → WorldChatService.send (UNCHANGED shape):
      · clean = sanitize(text)   // strip <0x20, cap to maxLength
      · localBroadcast: Server.broadcastMessage(worldId,
            PacketCreator.serverNotice(6, charName + ": " + clean))
      · chatId = worldChats.chatId(worldId)
      · if chatId.isPresent(): telegram.sendToChat(chatId.get(), charName + " " + clean)
  → TelegramClient.sendToChat:
      · bot.execute(new SendMessage(chatId, body), callback)
      · callback logs WARN on !response.isOk(), increments per-chat failure counter
```

#### Inbound: Telegram → game

```
Polling thread: bot.execute(GetUpdates().offset(lastId+1).timeout(25))
  → for each update:
      · skip if update.message() == null OR message.text() == null
      · chatId = message.chat().id()  (Long)
      · worldId = worldChats.worldFor(chatId)  // Optional<Integer>
      · if worldId is empty: skip (chat we don't bridge)
      · sender = displayNameFor(message.from())
            = "@" + username if username != null
            else (firstName + " " + lastName).strip()
            else "anon"
      · WorldChatService.deliverFromTelegram(worldId, sender, message.text())
  → deliverFromTelegram (mirrors deliverFromIrc):
      · clean = sanitize(text)
      · if clean.isEmpty(): drop
      · Server.broadcastMessage(worldId,
            PacketCreator.serverNotice(6, "[TG]" + sender + ": " + clean))
  → after the batch: lastId = max(message.update_id() observed); loop
```

### Configuration

New `telegram:` block in `config.yaml` (replaces `irc:`):

```yaml
telegram:
  enabled: false
  bot_token: ""                       # from @BotFather
  api_url: ""                         # optional override; "" = https://api.telegram.org
  poll_timeout_seconds: 25            # long-poll timeout (TG max 50)
  worldchat_rate_per_minute: 6
  worldchat_max_length: 200
  chats:                              # worldId → Telegram chat_id (negative for supergroups)
    0: -1001234567890
    1: -1001234567891
    2: -1001234567892
```

Validation rules (applied in `TelegramConfig.from`):
- `enabled=true` requires non-blank `bot_token` AND at least one entry in `chats`.
- `poll_timeout_seconds` clamped to `[1, 50]` (Telegram's max).
- `chats` keys coerced to `Integer`; values to `Long`. yamlbeans returns String/String regardless of declared types — we explicitly parse both, drop unparseable entries with one `WARN` per drop.
- `api_url`: empty falls back to the default Telegram endpoint; tests use it to redirect traffic to a `FakeTelegramApi`.

### Error handling and failure modes

| Failure | Behavior |
|---|---|
| `getUpdates` HTTP error / network down | Polling thread logs throttled `WARN` (≤1/min), waits 5s, retries the same offset. |
| 429 `Too Many Requests` | Respect `parameters.retry_after`; sleep that many seconds before retrying. |
| `sendMessage` failure | Async callback logs `WARN` with chat_id + reason. Drop the message; don't retry. Per-chat consecutive-failure counter. |
| Bot kicked (403 from `sendMessage`) | After 5 consecutive failures on the same chat, mute that chat (skip future sends to it) until restart. |
| Polling thread crash | Top-level `try/catch` logs `ERROR` + stacktrace; thread restarts after a 5s sleep. Never silently dies. |
| Malformed update | Logged at `DEBUG` and skipped; never propagates. |
| Inbound non-text | Silently dropped before `deliverFromTelegram` is called. |
| `@world` rate-limited | Silent drop, same as IRC. |
| Cosmic shutdown | `TelegramBridgeService.stop()` interrupts the polling thread, joins within 2s, logs `IRC bridge stopped` analog. |
| Unknown chat (TG message from a chat we don't bridge) | Skip silently. The bot may be in groups outside `telegram.chats`. |

### Testing strategy

| Layer | Test approach |
|---|---|
| `TelegramConfig` | Unit tests for parsing + validation; yamlbeans String-key coercion regression test. |
| `WorldChannelMap` | Existing tests adapt: values are `Long` instead of `String`; case-insensitive lookup is dropped (chat_ids are numeric). |
| `WorldChatService` | Existing tests carry over with a `FakeTelegramSender`; assert outbound enqueues + local broadcast both happen; non-text inbound is filtered before `deliverFromTelegram`; truncation works. |
| `WorldCommand` | Unchanged — the command is transport-agnostic. |
| `RateLimiter` | Unchanged. |
| `TelegramClient` HTTP I/O | `FakeTelegramApi` test harness — a `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0` implementing `POST /bot{token}/getUpdates` (returns canned JSON) and `POST /bot{token}/sendMessage` (records request body). pengrad's `TelegramBot.Builder().apiUrl(localFake)` directs traffic in-process. Tests cover: poll loop fetches and acknowledges updates; outbound `sendToChat` produces the expected POST; failure paths surface in callback. |
| `TelegramBridgeService` lifecycle | Integration test against `FakeTelegramApi`: `start()`, inject inbound update, assert local broadcast captured the right packet; call `WorldChatService.send(...)`, assert fake API saw the corresponding `sendMessage`; `stop()` joins polling thread within 2s. |

**Out of scope:**
- Real Telegram API in CI.
- Real TLS handshake to `api.telegram.org` (relies on JDK truststore + OkHttp defaults; manual smoke test against a real bot is the verification).
- Privacy-mode behavior (documentation, not testable here).

**Coverage target:** every new class has a unit/integration test class. JUnit 5, hand-rolled fakes, no Mockito — same conventions as the existing test suite.

## Operational notes

- **Default state:** `telegram.enabled: false`. Existing deployments are unaffected by upgrade aside from the IRC bridge being removed (which the deployer explicitly opts out of by leaving `enabled` false).
- **Bot privacy mode.** By default Bot API bots only see commands and `@bot` mentions in groups. To relay every message in the bridged groups, the deployer must turn off privacy mode in `@BotFather → /mybots → Bot Settings → Group Privacy → Turn off`. Then re-add the bot to each group (privacy is set per-session). README must call this out — without it the bridge appears one-way.
- **Bot membership.** The bot must be added to each chat listed in `telegram.chats`. No admin powers required.
- **Discovering chat_ids.** Quickest path: add the bot, send any message in the group, then `curl https://api.telegram.org/bot<token>/getUpdates` and read `result[0].message.chat.id`. Document this verbatim in the README.
- **Privacy.** World chat is fully visible to everyone in the bridged Telegram group. Players who don't want their character name on the group's history should not type `@world`. Same warning as IRC.

## Migration / removal

In-place replacement, single PR:

1. Delete `src/main/java/net/server/chat/irc/Irc{Connection,Config,LineParser,Message,Sender,BridgeService}.java`.
2. Delete the IRC tests under `src/test/java/net/server/chat/irc/Irc*Test.java` and `FakeIrcServer.java`.
3. Delete `src/main/java/config/IrcConfigYaml.java`.
4. Move + rename: `WorldChannelMap`, `WorldChatService`, `WorldBroadcaster`, `RateLimiter`, `WorldCommand` from `net.server.chat.irc` into `net.server.chat.telegram`. Adjust `WorldChannelMap` to use `Long` chat ids. Update tests' package + imports.
5. Add `TelegramConfig`, `TelegramConfigYaml`, `TelegramSender`, `TelegramClient`, `TelegramBridgeService`, `FakeTelegramApi`, plus their tests.
6. Replace the `irc:` block in `config.yaml` with the `telegram:` block.
7. Update the `Server.init()` boot block: replace `IrcConfig.from / IrcBridgeService.start` with the Telegram equivalents.
8. Remove the `ircd` service from `docker-compose.yml`.
9. Update the README's "IRC bridge" section into "Telegram bridge" with the bot-setup walkthrough (creating bot via `@BotFather`, disabling privacy mode, adding to groups, finding chat_ids).
10. Add `com.pengrad:java-telegram-bot-api:7.+` to `pom.xml`.

## Open questions deferred to plan

- Exact pengrad version pin (likely `7.11.0` as of 2026-05; pinning happens during plan/implementation).
- Whether to add a `RECONNECT` log line on getUpdates recovery (nice-to-have; can be a follow-up).
- Whether `currentBotUsername()` is fetched at startup via `bot.execute(GetMe)` or stored from the inbound flow — punted to plan.
