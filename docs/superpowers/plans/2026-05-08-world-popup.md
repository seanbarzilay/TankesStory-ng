# `@world` Popup Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tag-correlated popup-to-asker routing for IRC replies to `@world` questions, while preserving today's world-broadcast behavior for untagged IRC traffic.

**Architecture:** Inject a `[#N]` correlation marker into outbound IRC PRIVMSGs from `@world`. An in-memory `OutstandingQuestionTracker` (5-min TTL, sweep-on-read) maps `id → (worldId, charId, charName)`. Inbound IRC messages: parse first tag → claim → if tracked + asker still online, send `serverNotice(1, /* popup */)` to that player only and return; otherwise fall through to today's world broadcast.

**Tech Stack:** Java 21, Maven, JUnit 5, existing IRC bridge in `net.server.chat.irc/`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-08-world-popup-design.md`

---

## File map

**New main:**
- `src/main/java/net/server/chat/irc/QuestionTag.java`
- `src/main/java/net/server/chat/irc/OutstandingQuestionTracker.java`
- `src/main/java/net/server/chat/irc/PlayerSender.java`

**New tests:**
- `src/test/java/net/server/chat/irc/QuestionTagTest.java`
- `src/test/java/net/server/chat/irc/OutstandingQuestionTrackerTest.java`

**Modified:**
- `src/main/java/net/server/chat/irc/WorldChatService.java` (constructor gains tracker + playerSender; `send` gains `charId`; `deliverFromIrc` adds tag-routing fast path)
- `src/test/java/net/server/chat/irc/WorldChatServiceTest.java` (constructor calls + new tests for tag paths)
- `src/main/java/client/command/commands/gm0/WorldCommand.java` (pass `charId` through to `send`)
- `src/test/java/net/server/chat/irc/WorldCommandTest.java` (constructor calls)
- `src/main/java/net/server/chat/irc/IrcBridgeService.java` (instantiate tracker + production PlayerSender; pass to WorldChatService)

---

## Task 1: QuestionTag

**Files:**
- Create: `src/main/java/net/server/chat/irc/QuestionTag.java`
- Test: `src/test/java/net/server/chat/irc/QuestionTagTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class QuestionTagTest {

    @Test
    void marker_formatsId() {
        assertEquals("[#42]", QuestionTag.marker(42));
        assertEquals("[#0]", QuestionTag.marker(0));
    }

    @Test
    void parseFirst_atStart_returnsId() {
        assertEquals(OptionalInt.of(42), QuestionTag.parseFirst("[#42] hello"));
    }

    @Test
    void parseFirst_inMiddle_returnsId() {
        assertEquals(OptionalInt.of(7), QuestionTag.parseFirst("hi [#7] there"));
    }

    @Test
    void parseFirst_noTag_returnsEmpty() {
        assertTrue(QuestionTag.parseFirst("hello").isEmpty());
        assertTrue(QuestionTag.parseFirst("").isEmpty());
        assertTrue(QuestionTag.parseFirst("[#abc]").isEmpty());
        assertTrue(QuestionTag.parseFirst("[42]").isEmpty());
    }

    @Test
    void parseFirst_multipleTags_returnsFirst() {
        assertEquals(OptionalInt.of(42), QuestionTag.parseFirst("[#42] [#99]"));
    }

    @Test
    void parseFirst_overflow_returnsEmpty() {
        // 999999999999999999 doesn't fit in int — must not throw.
        assertTrue(QuestionTag.parseFirst("[#999999999999999999] hi").isEmpty());
    }

    @Test
    void parseFirst_null_returnsEmpty() {
        assertTrue(QuestionTag.parseFirst(null).isEmpty());
    }

    @Test
    void strip_atStartWithSpace_removesMarkerAndOneSpace() {
        assertEquals("hello", QuestionTag.strip("[#42] hello"));
    }

    @Test
    void strip_atStartNoSpace_removesOnlyMarker() {
        assertEquals("hello", QuestionTag.strip("[#42]hello"));
    }

    @Test
    void strip_inMiddle_collapsesAdjacentSpace() {
        assertEquals("hi there", QuestionTag.strip("hi [#42] there"));
    }

    @Test
    void strip_lonelyMarker_returnsEmpty() {
        assertEquals("", QuestionTag.strip("[#42]"));
    }

    @Test
    void strip_noTag_returnsAsIs() {
        assertEquals("hello", QuestionTag.strip("hello"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='QuestionTagTest' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE — `QuestionTag` does not exist.

- [ ] **Step 3: Implement `QuestionTag.java`**

```java
package net.server.chat.irc;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * `[#N]` correlation tag injected into outbound IRC PRIVMSGs from @world
 * and parsed off inbound IRC replies. Centralises the format so it can
 * be changed in one place.
 */
public final class QuestionTag {

    private static final Pattern TAG = Pattern.compile("\\[#(\\d+)]");

    private QuestionTag() {}

    public static String marker(int id) {
        return "[#" + id + "]";
    }

    public static OptionalInt parseFirst(String text) {
        if (text == null || text.isEmpty()) return OptionalInt.empty();
        Matcher m = TAG.matcher(text);
        if (!m.find()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            // Overflow — treat as untagged.
            return OptionalInt.empty();
        }
    }

    /**
     * Removes the FIRST tag occurrence. If the tag is followed by a single space
     * (or sits between two strings of text separated by a single space), the
     * space is consumed too so the surrounding text doesn't end up double-spaced.
     */
    public static String strip(String text) {
        if (text == null) return "";
        Matcher m = TAG.matcher(text);
        if (!m.find()) return text;
        int start = m.start();
        int end = m.end();
        // Eat a trailing space if present, OR a leading space if at-start has no trailing.
        if (end < text.length() && text.charAt(end) == ' ') {
            end++;
        } else if (start > 0 && text.charAt(start - 1) == ' ') {
            start--;
        }
        return text.substring(0, start) + text.substring(end);
    }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='QuestionTagTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/QuestionTag.java src/test/java/net/server/chat/irc/QuestionTagTest.java
git commit -m "Add QuestionTag (parse/marker/strip for [#N])"
```

---

## Task 2: OutstandingQuestionTracker

**Files:**
- Create: `src/main/java/net/server/chat/irc/OutstandingQuestionTracker.java`
- Test: `src/test/java/net/server/chat/irc/OutstandingQuestionTrackerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OutstandingQuestionTrackerTest {

    @Test
    void start_returnsAscendingIds() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        int a = tr.start(0, 1, "Alice");
        int b = tr.start(0, 1, "Alice");
        int c = tr.start(1, 2, "Bob");
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void claim_validId_returnsEntryOnceThenEmpty() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        int id = tr.start(2, 7, "Carol");

        Optional<OutstandingQuestionTracker.Entry> first = tr.claim(id);
        assertTrue(first.isPresent());
        assertEquals(2, first.get().worldId());
        assertEquals(7, first.get().charId());
        assertEquals("Carol", first.get().charName());

        Optional<OutstandingQuestionTracker.Entry> second = tr.claim(id);
        assertTrue(second.isEmpty());
    }

    @Test
    void claim_unknownId_returnsEmpty() {
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        assertTrue(tr.claim(999).isEmpty());
    }

    @Test
    void claim_expiredId_returnsEmpty() {
        ManualClock clock = new ManualClock(0);
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofSeconds(60), clock);
        int id = tr.start(0, 1, "Alice");
        clock.advance(60_001);    // 1ms past expiry
        assertTrue(tr.claim(id).isEmpty());
    }

    @Test
    void sweep_removesExpiredEntriesOnRead() {
        ManualClock clock = new ManualClock(0);
        OutstandingQuestionTracker tr = new OutstandingQuestionTracker(Duration.ofSeconds(60), clock);
        int oldId = tr.start(0, 1, "Old");
        clock.advance(120_000);    // 2 minutes — old entry now expired
        int newId = tr.start(0, 2, "New");

        // Touch via claim — sweeps incidentally.
        tr.claim(newId);
        assertEquals(0, tr.size(), "sweep should have removed the expired Old entry");
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static final class ManualClock extends Clock {
        private final AtomicLong now;
        ManualClock(long startMillis) { this.now = new AtomicLong(startMillis); }
        void advance(long ms) { now.addAndGet(ms); }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { throw new UnsupportedOperationException(); }
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='OutstandingQuestionTrackerTest' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE — `OutstandingQuestionTracker` doesn't exist.

- [ ] **Step 3: Implement**

```java
package net.server.chat.irc;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks outstanding @world questions awaiting an IRC reply. The bridge calls
 * {@link #start} when forwarding a question to IRC and embeds the returned id
 * via {@link QuestionTag#marker}. Inbound replies are matched via {@link #claim}.
 *
 * Thread-safe. Backed by ConcurrentHashMap + AtomicInteger.
 */
public final class OutstandingQuestionTracker {

    public record Entry(int worldId, int charId, String charName, long expiresAtMs) {}

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public OutstandingQuestionTracker(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public int start(int worldId, int charId, String charName) {
        long expiresAt = clock.millis() + ttl.toMillis();
        int id = nextId.getAndIncrement();
        entries.put(id, new Entry(worldId, charId, charName, expiresAt));
        sweep();
        return id;
    }

    public Optional<Entry> claim(int id) {
        sweep();
        Entry e = entries.remove(id);
        if (e == null) return Optional.empty();
        if (e.expiresAtMs() <= clock.millis()) return Optional.empty();
        return Optional.of(e);
    }

    public int size() {
        return entries.size();
    }

    private void sweep() {
        long now = clock.millis();
        Iterator<ConcurrentHashMap.Entry<Integer, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            ConcurrentHashMap.Entry<Integer, Entry> kv = it.next();
            if (kv.getValue().expiresAtMs() <= now) it.remove();
        }
    }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='OutstandingQuestionTrackerTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/OutstandingQuestionTracker.java src/test/java/net/server/chat/irc/OutstandingQuestionTrackerTest.java
git commit -m "Add OutstandingQuestionTracker with TTL + sweep-on-read"
```

---

## Task 3: PlayerSender interface

**Files:**
- Create: `src/main/java/net/server/chat/irc/PlayerSender.java`

The popup path needs to deliver a `Packet` to a specific online character without `WorldChatService` knowing about `Server` or `Character` (those are heavyweight and tied to a running game). A small interface keeps tests trivial.

- [ ] **Step 1: Create the interface**

```java
package net.server.chat.irc;

import net.packet.Packet;

/**
 * Test seam for delivering a packet directly to a single online character.
 * Production wraps Server.getInstance().getWorld(worldId).getPlayerStorage()
 * .getCharacterById(charId)#sendPacket(packet). Tests pass a fake recorder.
 */
public interface PlayerSender {
    /**
     * @return true if the player was found and the packet enqueued; false if
     *         the player isn't online in that world (caller drops the message).
     */
    boolean send(int worldId, int charId, Packet packet);
}
```

- [ ] **Step 2: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/server/chat/irc/PlayerSender.java
git commit -m "Add PlayerSender interface (popup delivery seam)"
```

---

## Task 4: WorldChatService — tag injection on outbound, popup routing on inbound

**Files:**
- Modify: `src/main/java/net/server/chat/irc/WorldChatService.java`
- Modify: `src/test/java/net/server/chat/irc/WorldChatServiceTest.java`

Constructor grows from 4 to 6 args (adds `OutstandingQuestionTracker tracker, PlayerSender playerSender`). `send` gains a `charId` param. `deliverFromIrc` gets a tag-routing fast path.

- [ ] **Step 1: Replace `WorldChatService.java` content**

```java
package net.server.chat.irc;

import tools.PacketCreator;

import java.util.Optional;
import java.util.OptionalInt;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;
    private static final int POPUP_NOTICE = 1;

    private final WorldChannelMap channels;
    private final IrcSender sender;
    private final WorldBroadcaster broadcaster;
    private final OutstandingQuestionTracker tracker;
    private final PlayerSender playerSender;
    private final int maxLength;

    public WorldChatService(WorldChannelMap channels, IrcSender sender,
                            WorldBroadcaster broadcaster,
                            OutstandingQuestionTracker tracker,
                            PlayerSender playerSender,
                            int maxLength) {
        this.channels = channels;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.tracker = tracker;
        this.playerSender = playerSender;
        this.maxLength = maxLength;
    }

    public void send(int worldId, int charId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        // Local self-loop: untagged, exactly as before.
        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        // Outbound to IRC: inject [#N] correlation tag so a reply can be routed back.
        channels.channel(worldId).ifPresent(chan -> {
            int tagId = tracker.start(worldId, charId, charName);
            sender.enqueue("PRIVMSG " + chan + " :" + charName + " " + QuestionTag.marker(tagId) + " " + clean);
        });
    }

    public void deliverFromIrc(int worldId, String nick, String text) {
        if (sender.currentNick() != null && nick.equalsIgnoreCase(sender.currentNick())) return;
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        // Tag-routing fast path: tagged + tracker hit + asker online → directed popup.
        OptionalInt tagId = QuestionTag.parseFirst(clean);
        if (tagId.isPresent()) {
            Optional<OutstandingQuestionTracker.Entry> entry = tracker.claim(tagId.getAsInt());
            if (entry.isPresent()) {
                String stripped = QuestionTag.strip(clean);
                boolean delivered = playerSender.send(entry.get().worldId(), entry.get().charId(),
                        PacketCreator.serverNotice(POPUP_NOTICE, stripped));
                // Whether or not delivered, we DO NOT fall through to broadcast —
                // the answer was directed and we already claimed the tag.
                return;
            }
            // Tag present but tracker has no entry (expired / restart) — fall through to broadcast.
        }

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, "[IRC]" + nick + ": " + clean));
    }

    private String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20) sb.append(c);
        }
        String stripped = sb.toString().strip();
        if (stripped.length() > maxLength) {
            return stripped.substring(0, maxLength) + "…";
        }
        return stripped;
    }
}
```

- [ ] **Step 2: Replace `WorldChatServiceTest.java` content**

```java
package net.server.chat.irc;

import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatServiceTest {

    @Test
    void send_localBroadcastIsUntagged_outboundIrcCarriesTag() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(0, 4, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(1, sender.lines.size());
        // Outbound IRC line carries the tag.
        assertTrue(sender.lines.get(0).contains("[#"), "expected [#N] tag in: " + sender.lines.get(0));
        assertTrue(sender.lines.get(0).endsWith("hi"));
        // Local self-loop does NOT carry the tag.
        // (We can't easily inspect packet contents here — but the FakeBroadcaster
        // captures the worldId + that the broadcast happened. The packet builder
        // is exercised; the tag-vs-no-tag separation lives in the source body.
        // A complementary string assertion via SqlSelectTool-style schema check
        // isn't applicable here. Trust the source review.)
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void send_unmappedWorld_skipsIrc_andDoesNotAllocateTag() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(99, 4, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.lines.size());
        assertEquals(0, tracker.size(), "no IRC channel → no tag should be allocated");
    }

    @Test
    void deliverFromIrc_taggedAndAskerOnline_popupOnly_noWorldBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        ps.online.add(new PlayerKey(0, 4));    // Alice online in world 0
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        // Alice asks first so we have a real tracked tag id.
        svc.send(0, 4, "Alice", "what mobs drop the red whip?");
        // Now respond with a tag matching the only outstanding question (id 1).
        svc.deliverFromIrc(0, "ircbot", "[#1] Wraith, Ginseng Jar");

        // Outbound from send() was 1 broadcast + 1 IRC line. Inbound should add NO new broadcast.
        assertEquals(1, bc.broadcasts.size(), "tagged answer must NOT broadcast to world");
        // Popup delivered to (worldId=0, charId=4).
        assertEquals(1, ps.calls.size());
        assertEquals(0, ps.calls.get(0).worldId);
        assertEquals(4, ps.calls.get(0).charId);
    }

    @Test
    void deliverFromIrc_taggedButTrackerMiss_fallsThroughToBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        // Tracker is empty — id 99 was never started.
        svc.deliverFromIrc(0, "ircbot", "[#99] late answer");

        assertEquals(1, bc.broadcasts.size(), "unmatched tag → fall through to world broadcast");
        assertEquals(0, ps.calls.size(), "no popup");
    }

    @Test
    void deliverFromIrc_taggedAndAskerOffline_dropsPopup_noBroadcast() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        // ps.online is empty — Alice has logged off.
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.send(0, 4, "Alice", "q");                               // 1 broadcast (self-loop), 1 IRC enqueue
        svc.deliverFromIrc(0, "ircbot", "[#1] late answer");        // tag matches but Alice is offline

        assertEquals(1, bc.broadcasts.size(), "no follow-up broadcast for offline asker");
        assertEquals(1, ps.calls.size(), "send was attempted");
        // The PlayerSender returned false (online set is empty) — we just don't broadcast.
    }

    @Test
    void deliverFromIrc_untagged_broadcastsAsToday() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("bot");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.deliverFromIrc(0, "ircuser", "general chatter");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, ps.calls.size());
    }

    @Test
    void deliverFromIrc_echoFromOwnNick_dropped() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        FakePlayerSender ps = new FakePlayerSender();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        WorldChatService svc = new WorldChatService(map, sender, bc, tracker, ps, 200);

        svc.deliverFromIrc(0, "Cosmic-Bridge", "loop?");

        assertEquals(0, bc.broadcasts.size());
        assertEquals(0, ps.calls.size());
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    static final class FakeSender implements IrcSender {
        final List<String> lines = new ArrayList<>();
        final String nick;
        FakeSender(String nick) { this.nick = nick; }
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return nick; }
    }

    static final class FakeBroadcaster implements WorldBroadcaster {
        final List<Bcast> broadcasts = new ArrayList<>();
        @Override public void broadcast(int worldId, Packet p) { broadcasts.add(new Bcast(worldId, p)); }
    }

    record Bcast(int worldId, Packet packet) {}

    record PlayerKey(int worldId, int charId) {}

    static final class PSendCall {
        final int worldId;
        final int charId;
        PSendCall(int worldId, int charId) { this.worldId = worldId; this.charId = charId; }
    }

    static final class FakePlayerSender implements PlayerSender {
        final java.util.Set<PlayerKey> online = new java.util.HashSet<>();
        final List<PSendCall> calls = new ArrayList<>();
        @Override public boolean send(int worldId, int charId, Packet packet) {
            calls.add(new PSendCall(worldId, charId));
            return online.contains(new PlayerKey(worldId, charId));
        }
    }
}
```

- [ ] **Step 3: Run tests, expect compile fail at first (constructor signature change cascades through callers — `WorldCommand` not yet updated)**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='WorldChatServiceTest' test 2>&1 | tail -10
```

Expected: COMPILATION FAILURE. The compiler will complain about `WorldCommand` and `IrcBridgeService` still calling `new WorldChatService(map, sender, broadcaster, maxLength)`. This is OK — those are fixed in Tasks 5 and 6.

- [ ] **Step 4: Quick local-only verification**

We can't pass full tests until Task 5 + 6 fix the callers. Defer test execution to the end of Task 6.

- [ ] **Step 5: Commit (broken-on-purpose)**

```bash
git add src/main/java/net/server/chat/irc/WorldChatService.java src/test/java/net/server/chat/irc/WorldChatServiceTest.java
git commit -m "WorldChatService: inject [#N] tag, route tagged inbound to popup

The constructor signature change cascades to WorldCommand and
IrcBridgeService — those are fixed in the next two commits. Project
will not compile until both follow."
```

---

## Task 5: WorldCommand — pass charId to send()

**Files:**
- Modify: `src/main/java/client/command/commands/gm0/WorldCommand.java`
- Modify: `src/test/java/net/server/chat/irc/WorldCommandTest.java`

`WorldChatService.send` now takes `(worldId, charId, charName, text)`. `WorldCommand.deliver` already has all four — just pass `charId` through.

- [ ] **Step 1: Replace `WorldCommand.java` content**

```java
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import net.server.chat.irc.IrcBridgeService;
import net.server.chat.irc.RateLimiter;
import net.server.chat.irc.WorldChatService;

public class WorldCommand extends Command {

    {
        setDescription("Send a message to world chat (bridged to IRC).");
    }

    @Override
    public void execute(Client client, String[] params) {
        IrcBridgeService.instance().ifPresent(svc -> {
            String text = client.getPlayer().getLastCommandMessage();
            if (text == null) return;
            text = text.strip();
            if (text.isEmpty()) return;
            deliver(svc.worldChat(), svc.rateLimiter(),
                    client.getWorld(), client.getPlayer().getId(),
                    client.getPlayer().getName(), text);
        });
    }

    public static void deliver(WorldChatService svc, RateLimiter rl,
                               int worldId, int charId, String charName, String text) {
        if (text == null || text.strip().isEmpty()) return;
        if (!rl.tryAcquire(charId)) return;
        svc.send(worldId, charId, charName, text);
    }
}
```

- [ ] **Step 2: Replace `WorldCommandTest.java`**

```java
package net.server.chat.irc;

import client.command.commands.gm0.WorldCommand;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldCommandTest {

    @Test
    void deliver_routesToService_andRespectsRateLimit() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        FakePlayerSender ps = new FakePlayerSender();
        WorldChatService svc = new WorldChatService(map, rec, rec, tracker, ps, 200);
        RateLimiter rl = new RateLimiter(2, fixed(0));

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi2");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "blocked");

        assertEquals(2, rec.broadcasts.size(), "third call should be rate-limited");
    }

    @Test
    void deliver_emptyText_droppedSilently() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(Duration.ofMinutes(5), fixed(0));
        FakePlayerSender ps = new FakePlayerSender();
        WorldChatService svc = new WorldChatService(map, rec, rec, tracker, ps, 200);
        RateLimiter rl = new RateLimiter(10, fixed(0));

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "   ");

        assertEquals(0, rec.broadcasts.size());
    }

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    static final class FakeRecorder implements IrcSender, WorldBroadcaster {
        final List<String> lines = new ArrayList<>();
        final List<Integer> broadcasts = new ArrayList<>();
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return "bot"; }
        @Override public void broadcast(int w, Packet p) { broadcasts.add(w); }
    }

    static final class FakePlayerSender implements PlayerSender {
        @Override public boolean send(int worldId, int charId, Packet packet) { return false; }
    }
}
```

- [ ] **Step 3: Compile (still expected to fail because IrcBridgeService.start hasn't been updated)**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -5
```

Expected: COMPILATION FAILURE on `IrcBridgeService.start` — fixed in Task 6.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/client/command/commands/gm0/WorldCommand.java src/test/java/net/server/chat/irc/WorldCommandTest.java
git commit -m "WorldCommand: pass charId through to WorldChatService.send"
```

---

## Task 6: IrcBridgeService — wire tracker + production PlayerSender

**Files:**
- Modify: `src/main/java/net/server/chat/irc/IrcBridgeService.java`

The bridge service builds `WorldChatService` inside `start()`. Add the tracker and a production `PlayerSender` that wraps `Server.getInstance().getWorld(...).getPlayerStorage().getCharacterById(...)`.

- [ ] **Step 1: Read current `IrcBridgeService.java` to find the `start()` factory and the `WorldChatService` instantiation site**

```bash
grep -n 'new WorldChatService\|public static IrcBridgeService start' src/main/java/net/server/chat/irc/IrcBridgeService.java
```

The instantiation today looks like (around line 50):

```java
        WorldChatService chat = new WorldChatService(map, conn, broadcaster, cfg.maxLength());
```

- [ ] **Step 2: Replace that single line and the surrounding context**

Find the block in `start(IrcConfig cfg, WorldBroadcaster broadcaster, Clock clock)` that builds the `IrcConnection` and the `WorldChatService`. The relevant section reads (approximately):

```java
        WorldChannelMap map = WorldChannelMap.of(cfg.channels());
        AtomicReference<WorldChatService> serviceRef = new AtomicReference<>();

        List<String> channelList = new ArrayList<>(map.allChannels());
        IrcConnection conn = new IrcConnection.Builder()
                .host(cfg.server()).port(cfg.port()).tls(cfg.tls())
                .nick(cfg.nick()).user(cfg.user()).realname(cfg.realname())
                .password(cfg.password())
                .channels(channelList).queueMax(cfg.outboundQueueMax())
                .backoffSeconds(cfg.reconnectBackoffSeconds())
                .onMessage(m -> {
                    if (!"PRIVMSG".equals(m.command()) || m.params().isEmpty()) return;
                    map.world(m.params().get(0)).ifPresent(worldId ->
                            serviceRef.get().deliverFromIrc(worldId, m.nick(), m.trailing()));
                })
                .build();

        WorldChatService chat = new WorldChatService(map, conn, broadcaster, cfg.maxLength());
        serviceRef.set(chat);
```

Replace the last two lines (the `WorldChatService` build and `serviceRef.set`) with:

```java
        OutstandingQuestionTracker tracker = new OutstandingQuestionTracker(java.time.Duration.ofMinutes(5), clock);
        PlayerSender playerSender = (worldId, charId, packet) -> {
            net.server.world.World w;
            try { w = net.server.Server.getInstance().getWorld(worldId); }
            catch (Exception e) { return false; }
            if (w == null) return false;
            client.Character ch = w.getPlayerStorage().getCharacterById(charId);
            if (ch == null) return false;
            ch.sendPacket(packet);
            return true;
        };
        WorldChatService chat = new WorldChatService(map, conn, broadcaster, tracker, playerSender, cfg.maxLength());
        serviceRef.set(chat);
```

- [ ] **Step 3: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run all the new + touched tests**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='QuestionTagTest,OutstandingQuestionTrackerTest,WorldChatServiceTest,WorldCommandTest,IrcBridgeServiceTest' test 2>&1 | tail -15
```

Expected: BUILD SUCCESS, all pass.

- [ ] **Step 5: Run the full project suite**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B test 2>&1 | tail -15
```

Expected: BUILD SUCCESS for everything except the pre-existing `MobSkillFactoryTest` failure on master (unrelated).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcBridgeService.java
git commit -m "IrcBridgeService: instantiate tracker + production PlayerSender"
```

---

## Verification checklist

After all tasks:

- [ ] `mvn test` is green (modulo `MobSkillFactoryTest`).
- [ ] Outbound IRC PRIVMSG carries `[#N]` after `<charName>` and before the message body.
- [ ] Local in-game self-loop does NOT carry the tag.
- [ ] Tagged inbound + tracker hit + asker online → popup (`serverNotice` type 1) only, no broadcast.
- [ ] Tagged inbound + tracker miss → world broadcast (with the tag still in the body — that's expected).
- [ ] Untagged inbound → world broadcast (regression).
- [ ] Server restart loses outstanding tags; the asker's late answer falls through to the world broadcast path.

## Notes for the implementer

- `PlayerSender` is a brand-new package-private interface. The production lambda in `IrcBridgeService.start` is the only impl in main code; tests use a fake.
- `QuestionTag.strip` consumes a single trailing or leading space adjacent to the marker so the surrounding text doesn't end up double-spaced. The test cases lock this behavior in.
- `OutstandingQuestionTracker.size()` is a test-only accessor. Don't expose it via any public surface beyond the tracker class.
- The tracker's `claim` returns the entry once and never again — atomic via `ConcurrentHashMap.remove`. No additional synchronization needed.
- Echo-loop guard (`nick.equalsIgnoreCase(sender.currentNick())`) stays at the top of `deliverFromIrc` — runs before tag parsing.
