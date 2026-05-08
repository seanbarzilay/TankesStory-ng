# `@world` Popup Response — Design

**Date:** 2026-05-08
**Status:** Draft (post-brainstorm, pre-plan)
**Scope:** Add tag-correlated popup-to-asker routing for IRC replies to `@world` questions. Untagged IRC chatter still broadcasts to the world as today.

## Background

The IRC bridge today treats every inbound IRC message identically: broadcast to the world as a lightblue chat-log line `[IRC]<nick>: <text>`. That's correct for general chat, but users want a tighter Q&A flow: a player asks a question via `@world <text>`; an IRC-side responder (LLM bot or human staffer) replies; only the original asker sees the answer, as a popup.

This spec adds correlation-by-tag and a directed-popup path. The general chat path is untouched.

## Goals

- When a player types `@world <text>`, Cosmic injects an opaque correlation marker (`[#42]`) into the outbound IRC PRIVMSG.
- When an IRC reply contains a recognised marker that's still tracked, Cosmic shows the reply text (tag stripped) as a popup to the asker only — not as a world broadcast.
- Untagged IRC traffic and tagged traffic for unknown / expired markers continue to broadcast to the world as today.
- The asker's local self-loop (the lightblue chat line their world sees of their own question) does **not** carry the tag.
- Asker offline at response time → drop the popup silently.

## Non-goals

- Persistence of outstanding questions across server restart.
- Multi-tag handling (`[#42] [#99]` in one message → first match wins, second tag is ignored).
- Convention enforcement on the responder side (we don't rewrite messages that lack a tag; the responder must echo the tag back).
- Configurable tag format or TTL value (constants in code; can be lifted later).
- Rendering of the popup beyond the response text — no responder identity is shown in the popup body. (If the responder identity matters, include it in the response text itself.)

## Key decisions (from brainstorm)

| Decision | Choice |
|---|---|
| Trigger | Inbound IRC message containing a recognised tag |
| Distribution | Popup to the original asker only — not a world broadcast |
| Correlation | Embedded numeric tag `[#N]` injected by Cosmic on outbound; responder echoes verbatim |
| Untagged inbound | Unchanged — broadcast to world as `[IRC]<nick>: <text>` |
| Asker offline at response time | Drop the popup silently |
| Tag TTL | 5 minutes (in-memory, sweep on read) |
| State across restart | Not persisted — outstanding tags are lost on restart, late responses fall through to world broadcast |

## Architecture

### Where it lives

All new code lives under `src/main/java/net/server/chat/irc/` alongside the existing IRC bridge. No new packages; no `Server.java` changes; no config block.

### Components

| Class | Role |
|---|---|
| `OutstandingQuestionTracker` *(new)* | Singleton. Owns `ConcurrentHashMap<Integer, Entry>` and `AtomicInteger nextId`. `start(worldId, charId, charName)` allocates the next id, stores `Entry(worldId, charId, charName, expiresAtMs)`, returns the id. `claim(int tagId)` removes-and-returns the entry if present and unexpired; sweeps any other expired entries on each call. Backed by an injectable `Clock` for tests. |
| `QuestionTag` *(new)* | Helper: `marker(int id) -> "[#" + id + "]"`. `parseFirst(String text) -> OptionalInt` — matches `\\[#(\\d+)\\]` anywhere in the text and returns the first id. `strip(String text) -> String` — removes the first marker plus a single following space if present, otherwise just the marker. |
| `WorldChatService.send(...)` *(modified)* | Allocates a tag via the tracker, sends `<charName> [#42] <text>` to IRC, self-loops the **untagged** `<charName>: <text>` to local players. Existing tests need a constructor change to accept the tracker. |
| `WorldChatService.deliverFromIrc(...)` *(modified)* | Calls `QuestionTag.parseFirst`. On a match: `tracker.claim(...)` → if entry returned AND asker still online in the same world, send a `serverNotice(1, /* popup */ stripped)` packet to that player only and return. Otherwise (no tag, expired, claim returned empty, asker offline) → fall through to today's `serverNotice(6, "[IRC]<nick>: <text>")` world broadcast. |

### Outbound data flow (game → IRC)

```
@world hi
  → WorldCommand.execute (UNCHANGED)
  → WorldChatService.send(worldId=0, charName="Alice", charId=4, text="hi"):
      · tag = tracker.start(0, 4, "Alice")  → e.g. 42
      · localBroadcast: serverNotice(6, "Alice: hi")          (no tag — local players)
      · IRC enqueue:    "PRIVMSG #cosmic-scania :Alice [#42] hi"
```

### Inbound data flow (IRC → game)

```
IrcConnection callback fires with PRIVMSG body "[#42] Wraith, Ginseng Jar"
  → WorldChatService.deliverFromIrc(0, "ircbot", "[#42] Wraith, Ginseng Jar"):
      · QuestionTag.parseFirst  → 42
      · entry = tracker.claim(42)  → Entry(worldId=0, charId=4, charName="Alice", ...)
      · player = world(0).getPlayerStorage().getCharacterById(4)
      · if entry != null AND player != null AND player.world == entry.worldId:
          player.sendPacket(serverNotice(1, QuestionTag.strip(text)))  // "Wraith, Ginseng Jar"
          return        // NO world broadcast
      · drop silently and DO NOT fall through to broadcast (the answer was directed)

  → For inbound without a tag OR with a tag that the tracker has no entry for
    (expired / restart / never tracked):
      · fall through to today's behavior:
          Server.broadcastMessage(0, serverNotice(6, "[IRC]ircbot: <full original text>"))
      · Note: when tracker has no entry, we DO broadcast — the tag is just ignored,
        the message is treated as general chatter that happens to contain bracket noise.
```

### Routing matrix

| Inbound message | Tracker has entry? | Asker online? | Popup to asker? | World broadcast? |
|---|---|---|---|---|
| Untagged | n/a | n/a | no | yes |
| Tagged | no (expired / never seen) | n/a | no | yes (full text incl. tag in body) |
| Tagged | yes | no | no | no (we claimed and dropped) |
| Tagged | yes | yes | yes | no |

### Threading

`OutstandingQuestionTracker` is hit from two threads: the game thread that calls `WorldChatService.send` (via `@world`), and the IRC poll thread that calls `WorldChatService.deliverFromIrc`. Backed by `ConcurrentHashMap` + `AtomicInteger` — the operations are independently atomic and we don't need cross-key transactions.

### Sweep policy

Each `claim` and each `start` triggers a single linear pass over the map to remove expired entries. Cheap given the expected map size (low double-digits at most — a 5-minute window across one server). No background timer needed.

## Error handling and edge cases

| Failure | Behavior |
|---|---|
| Tag in inbound but tracker has nothing for it (TTL expired / restart) | Fall through to world broadcast with the full original text. The bracketed digits are visible in chat — acceptable noise; alternative would be stripping the tag from the broadcast, but that hides what the responder typed. |
| Asker logged off between question and response | `tracker.claim` returns the entry (it was still alive), the player lookup returns null, drop silently. No world broadcast. |
| Asker world-changed (e.g. Scania → Bera) between question and response | The entry stores the original `worldId`. If the player is now online but in a different world, treat as offline and drop. |
| Multiple tags in one inbound message | First match wins; the rest are left as plain text in the popup body. |
| `claim` and a concurrent `claim` for the same tag | `ConcurrentHashMap.remove` is atomic; only one claim wins, the loser sees `null`. |
| Outbound IRC enqueue full | Existing throttled WARN; the tag entry stays in the tracker until TTL expiry. The asker's question never reached IRC, so no answer will arrive — the entry just expires harmlessly. |
| `QuestionTag.parseFirst` throws on absurd inputs (e.g. `[#999999999999999999]` overflow) | Wrap in try/catch and treat as no-tag; don't propagate. |

## Testing strategy

| Layer | Test approach |
|---|---|
| `QuestionTag` | Pure unit. `parseFirst("[#42] hi")` → 42; `parseFirst("hi [#42] there")` → 42 (anywhere); `parseFirst("hi")` → empty; `parseFirst("[#42] [#99]")` → 42; `parseFirst("[#abc]")` → empty; `parseFirst("")` → empty; `strip("[#42] hi")` → `"hi"`; `strip("[#42]")` → `""`; `strip("hi [#42] there")` → `"hi there"`. |
| `OutstandingQuestionTracker` | Unit with `ManualClock`. `start` returns ascending ids. `claim(unknownId)` → empty. `claim(validId)` → entry, second call → empty. `claim(expiredId)` → empty. Sweep removes other expired entries on call. |
| `WorldChatService.send` | Existing tests adapt to the new constructor. New assertions: outbound IRC line contains `[#N]`, local self-loop packet does NOT contain `[#`. |
| `WorldChatService.deliverFromIrc` | Four new test cases: (1) tagged + tracker hit + asker online → popup captured (assert serverNotice type=1) and world broadcast NOT captured; (2) tagged + tracker miss → world broadcast captured with original text; (3) tagged + tracker hit + asker offline → no popup, no broadcast; (4) untagged → world broadcast captured (regression). |

Test seams: `WorldChatService` gains a third dependency (`OutstandingQuestionTracker`) and a `PlayerLookup` interface (`(worldId, charId) -> Optional<Player>`) to keep the inbound path testable without booting `Server`. The production lookup is a one-liner around `Server.getInstance().getWorld(...).getPlayerStorage().getCharacterById(...)`. Tests pass a fake.

## Operational notes

- **Responder convention.** The IRC-side responder must include `[#N]` (verbatim from the question) in their reply for the popup to fire. Document this in the bot's prompt or a README note alongside the IRC channel description.
- **Tag visibility on IRC.** The `[#42]` marker is visible to every IRC user in the channel. That's intentional — humans reading the channel can see the correlation. If this becomes a UX issue, future work could move the tag to an IRC tag (IRCv3 message-tags), but our bridge is plain RFC 1459 / 2812 today and shouldn't change.
- **Default TTL = 5 minutes.** Roughly the longest interactive turn-around for an LLM responder. Short enough that the map can't grow unbounded; long enough that "I'll be right back, hold on" doesn't expire the question.
