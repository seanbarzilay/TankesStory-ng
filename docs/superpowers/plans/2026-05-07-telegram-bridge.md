# Cosmic Telegram Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the IRC bridge in place with a Telegram-Bot-API bridge that relays the in-game `@world` chat surface bidirectionally to one Telegram group per Cosmic world.

**Architecture:** Cosmic dials out to `api.telegram.org` via the `com.pengrad:java-telegram-bot-api` library. One polling thread runs `getUpdates(timeout=25)`; outbound `sendMessage` calls go async via the library's OkHttp pool — no separate writer thread, no outbound queue. The protocol-agnostic in-game pieces (`WorldChannelMap`, `WorldChatService`, `WorldCommand`, `RateLimiter`, `Server.init` boot block shape) carry over from the IRC bridge with minor type changes (chat ids become `Long`).

**Tech Stack:** Java 21, Maven, JUnit 5, `com.pengrad:java-telegram-bot-api:7.11.0`, existing project conventions (hand-rolled fakes, no Mockito, SLF4J).

**Spec:** `docs/superpowers/specs/2026-05-07-telegram-bridge-design.md`

---

## File map

**New main:**
- `src/main/java/config/TelegramConfigYaml.java`
- `src/main/java/net/server/chat/telegram/TelegramConfig.java`
- `src/main/java/net/server/chat/telegram/WorldChannelMap.java`
- `src/main/java/net/server/chat/telegram/WorldChatService.java`
- `src/main/java/net/server/chat/telegram/WorldBroadcaster.java`
- `src/main/java/net/server/chat/telegram/RateLimiter.java`
- `src/main/java/net/server/chat/telegram/TelegramSender.java`
- `src/main/java/net/server/chat/telegram/TelegramInbound.java`
- `src/main/java/net/server/chat/telegram/TelegramClient.java`
- `src/main/java/net/server/chat/telegram/TelegramBridgeService.java`

**New tests:**
- `src/test/java/net/server/chat/telegram/TelegramConfigTest.java`
- `src/test/java/net/server/chat/telegram/WorldChannelMapTest.java`
- `src/test/java/net/server/chat/telegram/WorldChatServiceTest.java`
- `src/test/java/net/server/chat/telegram/RateLimiterTest.java`
- `src/test/java/net/server/chat/telegram/WorldCommandTest.java`
- `src/test/java/net/server/chat/telegram/TelegramClientTest.java`
- `src/test/java/net/server/chat/telegram/TelegramBridgeServiceTest.java`
- `src/test/java/net/server/chat/telegram/FakeTelegramApi.java` *(test harness, not a test class)*

**Modified:**
- `pom.xml`
- `src/main/java/config/YamlConfig.java`
- `src/main/java/client/command/commands/gm0/WorldCommand.java`
- `src/main/java/net/server/Server.java`
- `config.yaml`
- `docker-compose.yml`
- `README.md`

**Deleted (the IRC bridge):**
- `src/main/java/config/IrcConfigYaml.java`
- `src/main/java/net/server/chat/irc/` (entire directory: `IrcConfig`, `IrcConnection`, `IrcLineParser`, `IrcMessage`, `IrcSender`, `IrcBridgeService`, `WorldChannelMap`, `WorldChatService`, `WorldBroadcaster`, `RateLimiter`)
- `src/test/java/net/server/chat/irc/` (entire directory)

---

## Task 1: Add pengrad dependency to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dependency**

Open `pom.xml`. Find the closing `</dependencies>` tag (around line 239). Insert the following block immediately before it:

```xml
        <dependency>
            <groupId>com.pengrad</groupId>
            <artifactId>java-telegram-bot-api</artifactId>
            <version>7.11.0</version>
        </dependency>
```

- [ ] **Step 2: Verify the dependency resolves**

Run (via the project's preferred Maven entry point — `./mvnw` if Java is available locally, or `podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B dependency:resolve` if not):

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B dependency:resolve 2>&1 | tail -3
```

Expected: BUILD SUCCESS (downloads `java-telegram-bot-api-7.11.0` and its transitive deps `okhttp` + `gson`).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "Add com.pengrad:java-telegram-bot-api 7.11.0"
```

---

## Task 2: TelegramConfigYaml POJO + YamlConfig wiring

**Files:**
- Create: `src/main/java/config/TelegramConfigYaml.java`
- Modify: `src/main/java/config/YamlConfig.java`

- [ ] **Step 1: Create `TelegramConfigYaml.java`** with this exact content:

```java
package config;

import java.util.Map;

public class TelegramConfigYaml {
    public boolean enabled;
    public String bot_token;
    public String api_url;
    public int poll_timeout_seconds;
    public int worldchat_rate_per_minute;
    public int worldchat_max_length;
    public Map<Integer, Long> chats;
}
```

- [ ] **Step 2: Add `telegram` field to `YamlConfig`**

Open `src/main/java/config/YamlConfig.java`. After the existing `public IrcConfigYaml irc;` line (around line 20), add:

```java
public TelegramConfigYaml telegram;
```

(The `irc` field is removed in Task 16; for now it stays so the IRC bridge keeps working until we cut over.)

- [ ] **Step 3: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/config/TelegramConfigYaml.java src/main/java/config/YamlConfig.java
git commit -m "Add TelegramConfigYaml POJO and wire into YamlConfig"
```

---

## Task 3: TelegramConfig validated wrapper + tests

**Files:**
- Create: `src/main/java/net/server/chat/telegram/TelegramConfig.java`
- Test: `src/test/java/net/server/chat/telegram/TelegramConfigTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/server/chat/telegram/TelegramConfigTest.java`:

```java
package net.server.chat.telegram;

import config.TelegramConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConfigTest {

    @Test
    void disabled_isValid_andHasNoChats() {
        TelegramConfigYaml yaml = new TelegramConfigYaml();
        yaml.enabled = false;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.enabled());
        assertTrue(cfg.isValid());
        assertEquals(0, cfg.chats().size());
    }

    @Test
    void enabled_requiresBotToken() {
        TelegramConfigYaml yaml = baseValid();
        yaml.bot_token = "";
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("bot_token"));
    }

    @Test
    void enabled_requiresAtLeastOneChat() {
        TelegramConfigYaml yaml = baseValid();
        yaml.chats = Map.of();
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("chats"));
    }

    @Test
    void pollTimeout_clampedToMaxFifty() {
        TelegramConfigYaml yaml = baseValid();
        yaml.poll_timeout_seconds = 999;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals(50, cfg.pollTimeoutSeconds());
    }

    @Test
    void pollTimeout_clampedToMinOne() {
        TelegramConfigYaml yaml = baseValid();
        yaml.poll_timeout_seconds = 0;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals(1, cfg.pollTimeoutSeconds());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void chats_acceptsStringKeysAndStringValuesAsYamlbeansEmits() {
        // yamlbeans deserializes nested-map keys/values as Strings even when
        // declared Map<Integer, Long>. Verify we coerce both sides.
        TelegramConfigYaml yaml = baseValid();
        Map raw = new HashMap();
        raw.put("0", "-1001234567890");
        raw.put("1", "-1001234567891");
        yaml.chats = raw;
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertTrue(cfg.isValid(), "validation error: " + cfg.validationError());
        assertEquals(2, cfg.chats().size());
        assertEquals(-1001234567890L, cfg.chats().get(0));
        assertEquals(-1001234567891L, cfg.chats().get(1));
    }

    @Test
    void apiUrl_emptyMeansDefault() {
        TelegramConfigYaml yaml = baseValid();
        yaml.api_url = "";
        TelegramConfig cfg = TelegramConfig.from(yaml);
        assertEquals("", cfg.apiUrl());
    }

    private TelegramConfigYaml baseValid() {
        TelegramConfigYaml y = new TelegramConfigYaml();
        y.enabled = true;
        y.bot_token = "12345:abcdef";
        y.api_url = "";
        y.poll_timeout_seconds = 25;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.chats = Map.of(0, -1001234567890L);
        return y;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramConfigTest' test 2>&1 | tail -10
```

Expected: COMPILATION FAILURE — `TelegramConfig` does not exist.

- [ ] **Step 3: Create `TelegramConfig.java`**

```java
package net.server.chat.telegram;

import config.TelegramConfigYaml;

import java.util.HashMap;
import java.util.Map;

public final class TelegramConfig {

    private static final int DEFAULT_POLL_TIMEOUT = 25;
    private static final int DEFAULT_RATE_PER_MINUTE = 6;
    private static final int DEFAULT_MAX_LENGTH = 200;

    private final boolean enabled;
    private final String botToken;
    private final String apiUrl;
    private final int pollTimeoutSeconds;
    private final int rateLimitPerMinute;
    private final int maxLength;
    private final Map<Integer, Long> chats;
    private final String validationError;

    private TelegramConfig(boolean enabled, String botToken, String apiUrl,
                           int pollTimeoutSeconds, int rateLimitPerMinute, int maxLength,
                           Map<Integer, Long> chats, String validationError) {
        this.enabled = enabled;
        this.botToken = botToken;
        this.apiUrl = apiUrl;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxLength = maxLength;
        this.chats = chats;
        this.validationError = validationError;
    }

    public static TelegramConfig from(TelegramConfigYaml y) {
        if (y == null || !y.enabled) {
            return new TelegramConfig(false, "", "", DEFAULT_POLL_TIMEOUT,
                    DEFAULT_RATE_PER_MINUTE, DEFAULT_MAX_LENGTH, Map.of(), null);
        }

        Map<Integer, Long> chats = coerceChats(y.chats);
        String err = validate(y, chats);

        int pollTimeout = clamp(y.poll_timeout_seconds <= 0 ? DEFAULT_POLL_TIMEOUT : y.poll_timeout_seconds, 1, 50);
        int ratePerMinute = y.worldchat_rate_per_minute > 0 ? y.worldchat_rate_per_minute : DEFAULT_RATE_PER_MINUTE;
        int maxLen = y.worldchat_max_length > 0 ? y.worldchat_max_length : DEFAULT_MAX_LENGTH;

        return new TelegramConfig(true,
                nullToEmpty(y.bot_token), nullToEmpty(y.api_url),
                pollTimeout, ratePerMinute, maxLen, chats, err);
    }

    private static String validate(TelegramConfigYaml y, Map<Integer, Long> chats) {
        if (y.bot_token == null || y.bot_token.isBlank()) return "bot_token is required";
        if (chats.isEmpty()) return "chats must not be empty";
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<Integer, Long> coerceChats(Map raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<Integer, Long> out = new HashMap<>();
        for (Object entry : raw.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            Object key = e.getKey();
            Object value = e.getValue();
            if (key == null || value == null) continue;
            int worldId;
            long chatId;
            try {
                worldId = (key instanceof Integer i) ? i : Integer.parseInt(key.toString());
                chatId = (value instanceof Long l) ? l
                        : (value instanceof Integer i) ? i.longValue()
                        : Long.parseLong(value.toString());
            } catch (NumberFormatException nfe) {
                continue;
            }
            out.put(worldId, chatId);
        }
        return Map.copyOf(out);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public boolean enabled() { return enabled; }
    public boolean isValid() { return enabled ? validationError == null : true; }
    public String validationError() { return validationError; }
    public String botToken() { return botToken; }
    public String apiUrl() { return apiUrl; }
    public int pollTimeoutSeconds() { return pollTimeoutSeconds; }
    public int rateLimitPerMinute() { return rateLimitPerMinute; }
    public int maxLength() { return maxLength; }
    public Map<Integer, Long> chats() { return chats; }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramConfigTest' test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/telegram/TelegramConfig.java src/test/java/net/server/chat/telegram/TelegramConfigTest.java
git commit -m "Add TelegramConfig with validation and yamlbeans key/value coercion"
```

---

## Task 4: Move WorldChannelMap to telegram package (Long values)

**Files:**
- Create: `src/main/java/net/server/chat/telegram/WorldChannelMap.java`
- Test: `src/test/java/net/server/chat/telegram/WorldChannelMapTest.java`

The IRC version uses `Map<Integer, String>` (channel name) with case-insensitive lookup. The Telegram version uses `Map<Integer, Long>` (chat id) — numeric, no case mapping.

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.telegram;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChannelMapTest {

    @Test
    void resolvesWorldToChatAndBack() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, -1001234567890L,
                1, -1001234567891L
        ));
        assertEquals(-1001234567890L, map.chatId(0).orElseThrow());
        assertEquals(1, map.worldFor(-1001234567891L).orElseThrow());
    }

    @Test
    void unmappedReturnsEmpty() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        assertTrue(map.chatId(99).isEmpty());
        assertTrue(map.worldFor(-9999L).isEmpty());
    }

    @Test
    void allChats_returnsAllRegistered() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, -1001234567890L,
                1, -1001234567891L
        ));
        assertEquals(2, map.allChats().size());
        assertTrue(map.allChats().contains(-1001234567890L));
        assertTrue(map.allChats().contains(-1001234567891L));
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='WorldChannelMapTest#resolvesWorldToChatAndBack' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE (the new test references a class in the new package).

- [ ] **Step 3: Implement**

```java
package net.server.chat.telegram;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class WorldChannelMap {

    private final Map<Integer, Long> worldToChat;
    private final Map<Long, Integer> chatToWorld;

    private WorldChannelMap(Map<Integer, Long> worldToChat, Map<Long, Integer> chatToWorld) {
        this.worldToChat = worldToChat;
        this.chatToWorld = chatToWorld;
    }

    public static WorldChannelMap of(Map<Integer, Long> raw) {
        Map<Integer, Long> w = new HashMap<>();
        Map<Long, Integer> c = new HashMap<>();
        for (Map.Entry<Integer, Long> e : raw.entrySet()) {
            w.put(e.getKey(), e.getValue());
            c.put(e.getValue(), e.getKey());
        }
        return new WorldChannelMap(Map.copyOf(w), Map.copyOf(c));
    }

    public Optional<Long> chatId(int worldId) {
        return Optional.ofNullable(worldToChat.get(worldId));
    }

    public Optional<Integer> worldFor(long chatId) {
        return Optional.ofNullable(chatToWorld.get(chatId));
    }

    public Collection<Long> allChats() {
        return worldToChat.values();
    }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='WorldChannelMapTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/telegram/WorldChannelMap.java src/test/java/net/server/chat/telegram/WorldChannelMapTest.java
git commit -m "Move WorldChannelMap to telegram package (Long chat ids)"
```

---

## Task 5: Move RateLimiter to telegram package

**Files:**
- Create: `src/main/java/net/server/chat/telegram/RateLimiter.java`
- Test: `src/test/java/net/server/chat/telegram/RateLimiterTest.java`

Pure carry-over — same code, different package. The IRC version stays in place until Task 16 deletes it.

- [ ] **Step 1: Create main file**

```java
package net.server.chat.telegram;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

    private final double tokensPerMs;
    private final double capacity;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        this.capacity = perMinute;
        this.tokensPerMs = perMinute / 60_000.0;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(int key) {
        long nowMs = clock.millis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowMs));
        long elapsed = nowMs - b.lastRefillMs;
        b.tokens = Math.min(capacity, b.tokens + elapsed * tokensPerMs);
        b.lastRefillMs = nowMs;
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return true;
        }
        return false;
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
        Bucket(double tokens, long lastRefillMs) {
            this.tokens = tokens;
            this.lastRefillMs = lastRefillMs;
        }
    }
}
```

- [ ] **Step 2: Create test**

```java
package net.server.chat.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsBurstUpToBucketCapacity() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(6, clock);
        for (int i = 0; i < 6; i++) assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void refillsOverTime() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(6, clock);
        for (int i = 0; i < 6; i++) rl.tryAcquire(42);
        assertFalse(rl.tryAcquire(42));
        clock.advance(10_000);
        assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void perCharacterIsolation() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(1, clock);
        assertTrue(rl.tryAcquire(1));
        assertFalse(rl.tryAcquire(1));
        assertTrue(rl.tryAcquire(2));
    }

    private static final class ManualClock extends Clock {
        private final AtomicLong nowMillis;
        ManualClock(long startMillis) { this.nowMillis = new AtomicLong(startMillis); }
        void advance(long delta) { nowMillis.addAndGet(delta); }
        @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis.get()); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { throw new UnsupportedOperationException(); }
    }
}
```

- [ ] **Step 3: Run test**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='net.server.chat.telegram.RateLimiterTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/server/chat/telegram/RateLimiter.java src/test/java/net/server/chat/telegram/RateLimiterTest.java
git commit -m "Move RateLimiter to telegram package (no behavior change)"
```

---

## Task 6: WorldBroadcaster, TelegramSender, TelegramInbound interfaces

**Files:**
- Create: `src/main/java/net/server/chat/telegram/WorldBroadcaster.java`
- Create: `src/main/java/net/server/chat/telegram/TelegramSender.java`
- Create: `src/main/java/net/server/chat/telegram/TelegramInbound.java`

- [ ] **Step 1: Create `WorldBroadcaster.java`**

```java
package net.server.chat.telegram;

import net.packet.Packet;

@FunctionalInterface
public interface WorldBroadcaster {
    void broadcast(int worldId, Packet packet);
}
```

- [ ] **Step 2: Create `TelegramSender.java`**

```java
package net.server.chat.telegram;

public interface TelegramSender {
    /** Fire-and-forget async send. Failures are logged in the implementation, never thrown. */
    void sendToChat(long chatId, String text);

    /** Bot's own username (with leading @). Used for parity with IRC's echo-loop guard;
     *  Telegram doesn't echo our own messages back through getUpdates, so this is informational. */
    String currentBotUsername();
}
```

- [ ] **Step 3: Create `TelegramInbound.java`**

```java
package net.server.chat.telegram;

/**
 * Decoded inbound text message from a bridged Telegram chat.
 * The polling thread builds these and hands them to WorldChatService.
 */
public record TelegramInbound(int worldId, String sender, String text) {
}
```

- [ ] **Step 4: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/telegram/WorldBroadcaster.java src/main/java/net/server/chat/telegram/TelegramSender.java src/main/java/net/server/chat/telegram/TelegramInbound.java
git commit -m "Add WorldBroadcaster, TelegramSender, TelegramInbound"
```

---

## Task 7: WorldChatService (telegram package, [TG] prefix)

**Files:**
- Create: `src/main/java/net/server/chat/telegram/WorldChatService.java`
- Test: `src/test/java/net/server/chat/telegram/WorldChatServiceTest.java`

Mirrors the IRC version: `send(...)` for game→TG fan-out (local self-loop + outbound to TG), `deliverFromTelegram(...)` for inbound (sanitize, broadcast as `[TG]<sender>: text`). Sanitize behavior — strip `<0x20`, no tab carve-out, truncate at maxLength — is unchanged.

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.telegram;

import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatServiceTest {

    @Test
    void send_fansOutToBothLocalAndTelegram() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@cosmic_bridge_bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(0, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
        assertEquals(1, sender.calls.size());
        assertEquals(-1001234567890L, sender.calls.get(0).chatId);
        assertEquals("Alice hi", sender.calls.get(0).text);
    }

    @Test
    void send_unmappedWorld_skipsTelegramButStillSelfLoops() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(99, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.calls.size());
    }

    @Test
    void deliverFromTelegram_broadcastsLightblueServerNoticeToWorld() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromTelegram(0, "@friend", "hello");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void deliverFromTelegram_emptyAfterSanitize_dropped() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromTelegram(0, "@friend", "   ");
        svc.deliverFromTelegram(0, "@friend", "");
        svc.deliverFromTelegram(0, "@friend", null);

        assertEquals(0, bc.broadcasts.size());
    }

    @Test
    void deliverFromTelegram_stripsControlCharsAndTruncates() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("@bot");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, sender, bc, 5);

        svc.deliverFromTelegram(0, "n", "ab\u0001cdef\u0002gh");
        // control chars stripped → "abcdefgh", truncated to 5 → "abcde…"
        assertEquals(1, bc.broadcasts.size());
    }

    static final class SendCall {
        final long chatId;
        final String text;
        SendCall(long chatId, String text) { this.chatId = chatId; this.text = text; }
    }

    static final class FakeSender implements TelegramSender {
        final List<SendCall> calls = new ArrayList<>();
        final String username;
        FakeSender(String username) { this.username = username; }
        @Override public void sendToChat(long chatId, String text) {
            calls.add(new SendCall(chatId, text));
        }
        @Override public String currentBotUsername() { return username; }
    }

    static final class FakeBroadcaster implements WorldBroadcaster {
        final List<Bcast> broadcasts = new ArrayList<>();
        @Override public void broadcast(int worldId, Packet p) {
            broadcasts.add(new Bcast(worldId, p));
        }
    }

    record Bcast(int worldId, Packet packet) {}
}
```

- [ ] **Step 2: Run, expect compile fail**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='net.server.chat.telegram.WorldChatServiceTest' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement**

```java
package net.server.chat.telegram;

import tools.PacketCreator;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;

    private final WorldChannelMap chats;
    private final TelegramSender sender;
    private final WorldBroadcaster broadcaster;
    private final int maxLength;

    public WorldChatService(WorldChannelMap chats, TelegramSender sender,
                            WorldBroadcaster broadcaster, int maxLength) {
        this.chats = chats;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.maxLength = maxLength;
    }

    public void send(int worldId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        chats.chatId(worldId).ifPresent(chatId ->
                sender.sendToChat(chatId, charName + " " + clean));
    }

    public void deliverFromTelegram(int worldId, String sender, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, "[TG]" + sender + ": " + clean));
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

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='net.server.chat.telegram.WorldChatServiceTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/telegram/WorldChatService.java src/test/java/net/server/chat/telegram/WorldChatServiceTest.java
git commit -m "Add WorldChatService for telegram (game ↔ TG fan-out)"
```

---

## Task 8: TelegramBridgeService stub + static accessor

**Files:**
- Create: `src/main/java/net/server/chat/telegram/TelegramBridgeService.java`

Stub only. Real lifecycle in Task 12.

- [ ] **Step 1: Create stub**

```java
package net.server.chat.telegram;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramBridgeService {

    private static final AtomicReference<TelegramBridgeService> INSTANCE = new AtomicReference<>();

    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    public TelegramBridgeService(WorldChatService worldChat, RateLimiter rateLimiter) {
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<TelegramBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(TelegramBridgeService svc) { INSTANCE.set(svc); }
    public static void clearInstance() { INSTANCE.set(null); }
}
```

- [ ] **Step 2: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/server/chat/telegram/TelegramBridgeService.java
git commit -m "Add TelegramBridgeService static-accessor stub"
```

---

## Task 9: Update WorldCommand to use telegram packages

**Files:**
- Modify: `src/main/java/client/command/commands/gm0/WorldCommand.java`
- Test: `src/test/java/net/server/chat/telegram/WorldCommandTest.java`

The command class itself stays in `client.command.commands.gm0` (its location is dictated by the `CommandsExecutor` package convention). Only its imports change — `IrcBridgeService` → `TelegramBridgeService`, `RateLimiter`/`WorldChatService` from the telegram package. The IRC bridge service still exists at this stage (deleted in Task 16) but it's no longer referenced.

- [ ] **Step 1: Replace `WorldCommand.java` content**

```java
package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import net.server.chat.telegram.RateLimiter;
import net.server.chat.telegram.TelegramBridgeService;
import net.server.chat.telegram.WorldChatService;

public class WorldCommand extends Command {

    {
        setDescription("Send a message to world chat (bridged to Telegram).");
    }

    @Override
    public void execute(Client client, String[] params) {
        TelegramBridgeService.instance().ifPresent(svc -> {
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
        svc.send(worldId, charName, text);
    }
}
```

- [ ] **Step 2: Create the test**

```java
package net.server.chat.telegram;

import client.command.commands.gm0.WorldCommand;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, rec, rec, 200);
        RateLimiter rl = new RateLimiter(2, fixedClock());

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "hi2");
        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "blocked");

        assertEquals(2, rec.broadcasts.size(), "third call should be rate-limited");
    }

    @Test
    void deliver_emptyText_droppedSilently() {
        FakeRecorder rec = new FakeRecorder();
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, -1001234567890L));
        WorldChatService svc = new WorldChatService(map, rec, rec, 200);
        RateLimiter rl = new RateLimiter(10, fixedClock());

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "   ");

        assertEquals(0, rec.broadcasts.size());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC);
    }

    static final class FakeRecorder implements TelegramSender, WorldBroadcaster {
        final List<String> sends = new ArrayList<>();
        final List<Integer> broadcasts = new ArrayList<>();
        @Override public void sendToChat(long chatId, String text) { sends.add(text); }
        @Override public String currentBotUsername() { return "@bot"; }
        @Override public void broadcast(int w, Packet p) { broadcasts.add(w); }
    }
}
```

- [ ] **Step 3: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='net.server.chat.telegram.WorldCommandTest' test 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/client/command/commands/gm0/WorldCommand.java src/test/java/net/server/chat/telegram/WorldCommandTest.java
git commit -m "Update WorldCommand to use telegram package imports"
```

---

## Task 10: FakeTelegramApi test harness

**Files:**
- Create: `src/test/java/net/server/chat/telegram/FakeTelegramApi.java`

A minimal in-process HTTP server that emulates the two Bot API endpoints we use. Handles `POST /bot{token}/getUpdates` and `POST /bot{token}/sendMessage`. The pengrad client points at it via `TelegramBot.Builder().apiUrl(localFakeUrl)`.

- [ ] **Step 1: Implement**

```java
package net.server.chat.telegram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process fake of the Telegram Bot API for tests. Listens on a random local
 * port; pengrad's TelegramBot.Builder().apiUrl("http://127.0.0.1:PORT") routes
 * traffic to it.
 *
 * Supports just /bot{token}/getUpdates and /bot{token}/sendMessage. Tests inject
 * canned getUpdates JSON via injectUpdate(...) and read recorded sendMessage
 * bodies via takeSentMessage(...).
 */
public final class FakeTelegramApi implements AutoCloseable {

    private final HttpServer server;
    private final LinkedBlockingQueue<String> sentMessages = new LinkedBlockingQueue<>();
    private final List<String> queuedUpdates = new ArrayList<>();   // JSON snippets

    public FakeTelegramApi() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::route);
        this.server.start();
    }

    public String urlBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Queue an update JSON snippet (the contents of a single element of the
     *  getUpdates "result" array). The next getUpdates call drains all queued
     *  updates and returns them. */
    public synchronized void injectUpdate(String updateJson) {
        queuedUpdates.add(updateJson);
    }

    public String takeSentMessage(long timeoutMs) throws InterruptedException {
        return sentMessages.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override public void close() { server.stop(0); }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/getUpdates")) {
            handleGetUpdates(ex);
        } else if (path.endsWith("/sendMessage")) {
            handleSendMessage(ex);
        } else {
            respond(ex, 404, "{\"ok\":false,\"description\":\"unknown\"}");
        }
    }

    private synchronized void handleGetUpdates(HttpExchange ex) throws IOException {
        StringBuilder body = new StringBuilder("{\"ok\":true,\"result\":[");
        for (int i = 0; i < queuedUpdates.size(); i++) {
            if (i > 0) body.append(',');
            body.append(queuedUpdates.get(i));
        }
        body.append("]}");
        queuedUpdates.clear();
        respond(ex, 200, body.toString());
    }

    private void handleSendMessage(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        sentMessages.offer(body);
        respond(ex, 200,
                "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":0," +
                "\"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "\"text\":\"\"}}");
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
```

- [ ] **Step 2: Compile-only sanity**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q test-compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS (no test executed; it's a harness).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/net/server/chat/telegram/FakeTelegramApi.java
git commit -m "Add FakeTelegramApi test harness"
```

---

## Task 11: TelegramClient (polling thread + async send)

**Files:**
- Create: `src/main/java/net/server/chat/telegram/TelegramClient.java`
- Test: `src/test/java/net/server/chat/telegram/TelegramClientTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.telegram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TelegramClientTest {

    private FakeTelegramApi fake;
    private TelegramClient client;

    @AfterEach
    void teardown() throws Exception {
        if (client != null) client.stop(2000);
        if (fake != null) fake.close();
    }

    @Test
    void inboundTextMessage_invokesListener() throws Exception {
        fake = new FakeTelegramApi();
        AtomicReference<TelegramClient.RawMessage> got = new AtomicReference<>();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(got::set)
                .build();
        client.start();

        fake.injectUpdate(
                "{\"update_id\":100," +
                " \"message\":{\"message_id\":1,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\",\"username\":\"alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"text\":\"hello world\"}}");

        long deadline = System.currentTimeMillis() + 4000;
        while (got.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertNotNull(got.get(), "listener never fired");
        assertEquals(-1001L, got.get().chatId);
        assertEquals("hello world", got.get().text);
        assertEquals("alice", got.get().fromUsername);
        assertEquals("Alice", got.get().fromFirstName);
    }

    @Test
    void sendToChat_postsToFakeApi() throws Exception {
        fake = new FakeTelegramApi();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(m -> {})
                .build();
        client.start();

        client.sendToChat(-1001L, "Alice hello");

        String body = fake.takeSentMessage(2000);
        assertNotNull(body, "no sendMessage POST received");
        assertTrue(body.contains("\"chat_id\":-1001"), "body=" + body);
        assertTrue(body.contains("Alice hello"), "body=" + body);
    }

    @Test
    void nonTextUpdate_dropped() throws Exception {
        fake = new FakeTelegramApi();
        AtomicReference<TelegramClient.RawMessage> got = new AtomicReference<>();
        client = new TelegramClient.Builder()
                .botToken("test:token")
                .apiUrl(fake.urlBase())
                .pollTimeoutSeconds(1)
                .onMessage(got::set)
                .build();
        client.start();

        // sticker update — no `text` field
        fake.injectUpdate(
                "{\"update_id\":101," +
                " \"message\":{\"message_id\":2,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"sticker\":{\"file_id\":\"x\",\"emoji\":\"🎉\"}}}");

        Thread.sleep(2500);
        assertNull(got.get(), "listener fired for a non-text update");
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramClientTest' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE — `TelegramClient` doesn't exist.

- [ ] **Step 3: Implement `TelegramClient.java`**

```java
package net.server.chat.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class TelegramClient implements TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    /** Decoded inbound message; the bridge service maps chatId -> worldId
     *  and turns this into a TelegramInbound for WorldChatService. */
    public record RawMessage(long chatId, String fromUsername, String fromFirstName,
                             String fromLastName, String text) {}

    private final TelegramBot bot;
    private final int pollTimeoutSeconds;
    private final Consumer<RawMessage> onMessage;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);
    private final AtomicLong lastWarnAtMs = new AtomicLong(0);
    private volatile String botUsername = "";
    private volatile Thread pollThread;

    private TelegramClient(Builder b) {
        TelegramBot.Builder bb = new TelegramBot.Builder(b.botToken);
        if (b.apiUrl != null && !b.apiUrl.isBlank()) {
            bb.apiUrl(b.apiUrl + "/bot");
        }
        this.bot = bb.build();
        this.pollTimeoutSeconds = b.pollTimeoutSeconds;
        this.onMessage = b.onMessage;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        pollThread = new Thread(this::pollLoop, "telegram-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void stop(long timeoutMs) {
        if (!running.compareAndSet(true, false)) return;
        if (pollThread != null) pollThread.interrupt();
        try {
            if (pollThread != null) pollThread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void sendToChat(long chatId, String text) {
        if (!running.get()) return;
        try {
            bot.execute(new SendMessage(chatId, text), new com.pengrad.telegrambot.utility.Callback<>() {
                @Override public void onResponse(SendMessage req, com.pengrad.telegrambot.response.SendResponse resp) {
                    if (!resp.isOk()) {
                        long now = System.currentTimeMillis();
                        long last = lastWarnAtMs.get();
                        if (now - last >= 60_000 && lastWarnAtMs.compareAndSet(last, now)) {
                            log.warn("telegram sendMessage rejected: chat={} code={} desc={}",
                                    chatId, resp.errorCode(), resp.description());
                        }
                    }
                }
                @Override public void onFailure(SendMessage req, java.io.IOException e) {
                    long now = System.currentTimeMillis();
                    long last = lastWarnAtMs.get();
                    if (now - last >= 60_000 && lastWarnAtMs.compareAndSet(last, now)) {
                        log.warn("telegram sendMessage failed: chat={} err={}", chatId, e.toString());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("telegram sendMessage threw synchronously: {}", e.toString());
        }
    }

    @Override public String currentBotUsername() { return botUsername; }

    private void pollLoop() {
        while (running.get()) {
            try {
                GetUpdatesResponse resp = bot.execute(
                        new GetUpdates()
                                .offset((int) (lastUpdateId.get() + 1))
                                .timeout(pollTimeoutSeconds));
                if (resp == null || !resp.isOk() || resp.updates() == null) {
                    sleepBackoff(5000);
                    continue;
                }
                for (com.pengrad.telegrambot.model.Update u : resp.updates()) {
                    long uid = u.updateId();
                    if (uid > lastUpdateId.get()) lastUpdateId.set(uid);
                    Message m = u.message();
                    if (m == null) continue;
                    String text = m.text();
                    if (text == null) continue;
                    User from = m.from();
                    onMessage.accept(new RawMessage(
                            m.chat().id(),
                            from == null ? null : from.username(),
                            from == null ? null : from.firstName(),
                            from == null ? null : from.lastName(),
                            text));
                }
            } catch (Exception e) {
                if (running.get()) {
                    long now = System.currentTimeMillis();
                    long last = lastWarnAtMs.get();
                    if (now - last >= 60_000 && lastWarnAtMs.compareAndSet(last, now)) {
                        log.warn("telegram poll loop error: {}", e.toString());
                    }
                    sleepBackoff(5000);
                }
            }
        }
    }

    private void sleepBackoff(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static final class Builder {
        private String botToken = "";
        private String apiUrl = "";
        private int pollTimeoutSeconds = 25;
        private Consumer<RawMessage> onMessage = m -> {};

        public Builder botToken(String v) { this.botToken = v; return this; }
        public Builder apiUrl(String v) { this.apiUrl = v; return this; }
        public Builder pollTimeoutSeconds(int v) { this.pollTimeoutSeconds = v; return this; }
        public Builder onMessage(Consumer<RawMessage> v) { this.onMessage = v; return this; }
        public TelegramClient build() { return new TelegramClient(this); }
    }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramClientTest' test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/telegram/TelegramClient.java src/test/java/net/server/chat/telegram/TelegramClientTest.java
git commit -m "Add TelegramClient (poll thread + async send via pengrad)"
```

---

## Task 12: TelegramBridgeService full lifecycle

**Files:**
- Modify (REPLACE the stub at): `src/main/java/net/server/chat/telegram/TelegramBridgeService.java`
- Test: `src/test/java/net/server/chat/telegram/TelegramBridgeServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.telegram;

import client.command.CommandsExecutor;
import config.TelegramConfigYaml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramBridgeServiceTest {

    private FakeTelegramApi fake;
    private TelegramBridgeService svc;

    @AfterEach
    void teardown() throws Exception {
        if (svc != null) svc.stop(2000);
        if (fake != null) fake.close();
        TelegramBridgeService.clearInstance();
        CommandsExecutor.getInstance().unregisterCommand("world");
    }

    @Test
    void start_thenSendRoutesToTelegram() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));

        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());

        svc.worldChat().send(0, "Alice", "hi");

        String body = fake.takeSentMessage(2000);
        assertNotNull(body, "no sendMessage POST received");
        assertTrue(body.contains("Alice hi"), "body=" + body);
        assertTrue(body.contains("\"chat_id\":-1001"), "body=" + body);
        assertEquals(1, broadcasts.size());
    }

    @Test
    void inboundTextDeliveredAsLocalBroadcast() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));
        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());

        fake.injectUpdate(
                "{\"update_id\":100," +
                " \"message\":{\"message_id\":1,\"date\":0," +
                "  \"from\":{\"id\":42,\"is_bot\":false,\"first_name\":\"Alice\",\"username\":\"alice\"}," +
                "  \"chat\":{\"id\":-1001,\"type\":\"supergroup\",\"title\":\"t\"}," +
                "  \"text\":\"hi back\"}}");

        long deadline = System.currentTimeMillis() + 4000;
        while (broadcasts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, broadcasts.size(), "no inbound broadcast");
        assertEquals(0, broadcasts.get(0)[0]);
    }

    @Test
    void start_registersWorldCommand_andStopUnregistersIt() throws Exception {
        fake = new FakeTelegramApi();
        TelegramConfig cfg = TelegramConfig.from(yaml(fake.urlBase()));
        WorldBroadcaster bc = (w, p) -> {};

        svc = TelegramBridgeService.start(cfg, bc, Clock.systemUTC());
        assertTrue(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));

        svc.stop(2000);
        svc = null;
        assertFalse(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));
    }

    private TelegramConfigYaml yaml(String apiUrl) {
        TelegramConfigYaml y = new TelegramConfigYaml();
        y.enabled = true;
        y.bot_token = "test:token";
        y.api_url = apiUrl;
        y.poll_timeout_seconds = 1;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.chats = Map.of(0, -1001L);
        return y;
    }
}
```

- [ ] **Step 2: Run, expect fail**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramBridgeServiceTest' test 2>&1 | tail -5
```

Expected: COMPILATION FAILURE — `TelegramBridgeService.start(...)` does not exist.

- [ ] **Step 3: Replace `TelegramBridgeService.java` with the full impl**

```java
package net.server.chat.telegram;

import client.command.CommandsExecutor;
import client.command.commands.gm0.WorldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramBridgeService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBridgeService.class);
    private static final AtomicReference<TelegramBridgeService> INSTANCE = new AtomicReference<>();

    private final TelegramClient client;
    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    private TelegramBridgeService(TelegramClient client, WorldChatService worldChat, RateLimiter rateLimiter) {
        this.client = client;
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<TelegramBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(TelegramBridgeService svc) { INSTANCE.set(svc); }
    public static void clearInstance() { INSTANCE.set(null); }

    public static TelegramBridgeService start(TelegramConfig cfg, WorldBroadcaster broadcaster, Clock clock) {
        if (!cfg.enabled()) {
            log.info("Telegram bridge disabled");
            return null;
        }
        if (!cfg.isValid()) {
            log.warn("Telegram bridge enabled but config is invalid: {}", cfg.validationError());
            return null;
        }

        WorldChannelMap chats = WorldChannelMap.of(cfg.chats());
        AtomicReference<WorldChatService> serviceRef = new AtomicReference<>();

        TelegramClient client = new TelegramClient.Builder()
                .botToken(cfg.botToken())
                .apiUrl(cfg.apiUrl())
                .pollTimeoutSeconds(cfg.pollTimeoutSeconds())
                .onMessage(raw -> {
                    chats.worldFor(raw.chatId()).ifPresent(worldId -> {
                        String displayName = displayNameFor(raw);
                        serviceRef.get().deliverFromTelegram(worldId, displayName, raw.text());
                    });
                })
                .build();

        WorldChatService chat = new WorldChatService(chats, client, broadcaster, cfg.maxLength());
        serviceRef.set(chat);

        RateLimiter rl = new RateLimiter(cfg.rateLimitPerMinute(), clock);
        TelegramBridgeService svc = new TelegramBridgeService(client, chat, rl);
        setInstance(svc);

        CommandsExecutor.getInstance().registerLv0Command("world", WorldCommand.class);
        client.start();
        log.info("Telegram bridge started: chats={}", cfg.chats());
        return svc;
    }

    private static String displayNameFor(TelegramClient.RawMessage raw) {
        if (raw.fromUsername() != null && !raw.fromUsername().isBlank()) {
            return "@" + raw.fromUsername();
        }
        StringBuilder sb = new StringBuilder();
        if (raw.fromFirstName() != null) sb.append(raw.fromFirstName());
        if (raw.fromLastName() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(raw.fromLastName());
        }
        String name = sb.toString().strip();
        return name.isEmpty() ? "anon" : name;
    }

    public void stop(long timeoutMs) {
        try {
            CommandsExecutor.getInstance().unregisterCommand("world");
        } catch (Exception e) {
            log.warn("failed to unregister @world: {}", e.toString());
        }
        client.stop(timeoutMs);
        clearInstance();
        log.info("Telegram bridge stopped");
    }
}
```

- [ ] **Step 4: Run test, expect pass**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramBridgeServiceTest' test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Run the new package's full test set**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -Dtest='TelegramConfigTest,WorldChannelMapTest,RateLimiterTest,WorldChatServiceTest,WorldCommandTest,TelegramClientTest,TelegramBridgeServiceTest' test 2>&1 | tail -10
```

Expected: BUILD SUCCESS, all tests pass. (The `RateLimiterTest` etc. may collide with the IRC versions of the same class names; if so, qualify by package: `net.server.chat.telegram.RateLimiterTest`.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/server/chat/telegram/TelegramBridgeService.java src/test/java/net/server/chat/telegram/TelegramBridgeServiceTest.java
git commit -m "Wire TelegramBridgeService lifecycle (client + service + command)"
```

---

## Task 13: Server.init() boot block + shutdown hook

**Files:**
- Modify: `src/main/java/net/server/Server.java`

The IRC boot block currently lives between the MCP catch and the channel-reload loop. Replace it with the Telegram boot block. The IRC bridge code still exists at this point (deleted in Task 16) but stops being referenced.

- [ ] **Step 1: Replace the IRC boot block with the Telegram boot block**

In `src/main/java/net/server/Server.java`, find the existing IRC block (search for `net.server.chat.irc.IrcConfig`):

```java
        try {
            net.server.chat.irc.IrcConfig ircConfig = net.server.chat.irc.IrcConfig.from(YamlConfig.config.irc);
            if (ircConfig.enabled()) {
                if (!ircConfig.isValid()) {
                    log.warn("IRC bridge enabled but config is invalid: {}", ircConfig.validationError());
                } else {
                    net.server.chat.irc.IrcBridgeService.start(ircConfig, this::broadcastMessage, java.time.Clock.systemUTC());
                }
            }
        } catch (Exception e) {
            log.warn("IRC bridge failed to start", e);
        }
```

Replace it with:

```java
        try {
            net.server.chat.telegram.TelegramConfig tgConfig = net.server.chat.telegram.TelegramConfig.from(YamlConfig.config.telegram);
            if (tgConfig.enabled()) {
                if (!tgConfig.isValid()) {
                    log.warn("Telegram bridge enabled but config is invalid: {}", tgConfig.validationError());
                } else {
                    net.server.chat.telegram.TelegramBridgeService.start(tgConfig, this::broadcastMessage, java.time.Clock.systemUTC());
                }
            }
        } catch (Exception e) {
            log.warn("Telegram bridge failed to start", e);
        }
```

- [ ] **Step 2: Replace the shutdown hook**

Find:

```java
        try {
            net.server.chat.irc.IrcBridgeService.instance().ifPresent(b -> b.stop(2000));
        } catch (Exception e) {
            log.warn("IRC bridge shutdown error", e);
        }
```

Replace with:

```java
        try {
            net.server.chat.telegram.TelegramBridgeService.instance().ifPresent(b -> b.stop(2000));
        } catch (Exception e) {
            log.warn("Telegram bridge shutdown error", e);
        }
```

- [ ] **Step 3: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run full project tests**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B test 2>&1 | tail -10
```

Expected: BUILD SUCCESS. (The pre-existing `MobSkillFactoryTest` failure on master may still appear; ignore it — unrelated.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/Server.java
git commit -m "Switch Server.init from IRC bridge to Telegram bridge"
```

---

## Task 14: config.yaml block + docker-compose ircd removal

**Files:**
- Modify: `config.yaml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace `irc:` block in `config.yaml`**

Find the `irc:` block (the entire multi-line block from `irc:` through `reconnect_backoff_seconds: [...]`). Replace it with:

```yaml
telegram:
  enabled: false
  bot_token: ""
  api_url: ""
  poll_timeout_seconds: 25
  worldchat_rate_per_minute: 6
  worldchat_max_length: 200
  chats:
    0: -1001234567890
    1: -1001234567891
    2: -1001234567892
```

- [ ] **Step 2: Remove the `ircd` service from `docker-compose.yml`**

Find and delete the entire `ircd:` service block (added in PR #13):

```yaml
  # Optional self-hosted IRC server for the in-game @world bridge.
  # To enable: set irc.enabled: true in config.yaml and point irc.server
  # at this service's compose hostname (`ircd`) on port 6667 (no TLS).
  ircd:
    image: ergochat/ergo:latest
    restart: unless-stopped
    ports:
      - "6667:6667"
```

- [ ] **Step 3: Validate compose**

```bash
podman run --rm -v "$PWD":/work:Z -w /work docker.io/docker/compose:latest -f docker-compose.yml config --services 2>&1 | tail -3
```

Expected output: `db` and `maplestory` (no `ircd`).

(If the docker/compose image is unavailable, skip this step and rely on a deploy-time `docker compose config --services` check.)

- [ ] **Step 4: Commit**

```bash
git add config.yaml docker-compose.yml
git commit -m "Replace irc: config block with telegram: and drop ircd compose service"
```

---

## Task 15: README update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the IRC bridge section**

Find the section starting with `#### IRC bridge`. Replace it (and its body, up to the next `####` heading) with:

```markdown
#### Telegram bridge

Cosmic can bridge a new world-wide chat surface bidirectionally to a Telegram group per world. Players use `@world <text>` in-game; Telegram users in the matching group see `<PlayerName> text` posted by the bot. Inbound Telegram traffic appears in-game as a lightblue chat-log line: `[TG]<sender>: text` (sender is `@username` if the user has one, else first+last name, else `anon`). **Disabled by default.**

To enable:

1. Talk to `@BotFather` on Telegram. `/newbot` — pick a name and username, save the bot token.
2. Disable privacy mode for the bot: `@BotFather → /mybots → <your bot> → Bot Settings → Group Privacy → Turn off`. Without this, the bot only sees `/`-commands and `@bot` mentions and inbound traffic appears empty.
3. Add the bot to each Telegram group you want bridged. Note the chat id by sending any message in the group, then `curl https://api.telegram.org/bot<token>/getUpdates` and reading `result[0].message.chat.id` (a negative number for supergroups).
4. Set `telegram.enabled: true` and configure the rest in `config.yaml`:

```yaml
telegram:
  enabled: true
  bot_token: "12345:abc..."
  poll_timeout_seconds: 25
  chats:
    0: -1001234567890
```

Cosmic dials out to `api.telegram.org` via long polling — no inbound port to open, no webhook server. The bridge tolerates Telegram-side outages: in-game `@world` traffic still reaches local players via a self-loop while the polling thread retries with backoff. `@world` is rate-limited per character (default 6/min) and length-capped at 200 chars.

**Privacy note:** world chat is fully visible to everyone in the bridged Telegram group. Players who don't want their character name on the group's history should not type `@world`.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Document Telegram bridge in README (replaces IRC section)"
```

---

## Task 16: Delete IRC bridge code

**Files:**
- Delete: `src/main/java/config/IrcConfigYaml.java`
- Delete: `src/main/java/net/server/chat/irc/` (entire directory)
- Delete: `src/test/java/net/server/chat/irc/` (entire directory)
- Modify: `src/main/java/config/YamlConfig.java`

- [ ] **Step 1: Remove the `irc` field from `YamlConfig`**

Open `src/main/java/config/YamlConfig.java`. Find and delete this line:

```java
public IrcConfigYaml irc;
```

- [ ] **Step 2: Delete the IRC sources and tests**

```bash
git rm src/main/java/config/IrcConfigYaml.java
git rm -r src/main/java/net/server/chat/irc
git rm -r src/test/java/net/server/chat/irc
```

- [ ] **Step 3: Compile**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B -q compile 2>&1 | tail -3
```

Expected: BUILD SUCCESS. If the compile fails because anything else still references `net.server.chat.irc.*`, grep for those refs and fix them — but if Tasks 9 + 13 were applied correctly, nothing else should reference IRC anymore.

- [ ] **Step 4: Run full project tests**

```bash
podman run --rm -v "$PWD":/work:Z -w /work maven:3.9.6-amazoncorretto-21 mvn -B test 2>&1 | tail -15
```

Expected: BUILD SUCCESS, all tests pass (modulo the pre-existing `MobSkillFactoryTest` failure on master).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Delete IRC bridge code (replaced by Telegram bridge)"
```

---

## Verification checklist

After all tasks:

- [ ] `mvn test` is green via podman.
- [ ] `pom.xml` has the new pengrad dep; nothing else added.
- [ ] `git ls-files | grep '/irc/'` returns no matches.
- [ ] `tools/list` on the running server (after deploy + reconnect) does not include any new MCP tools (the bridge does not add MCP tools).
- [ ] With `telegram.enabled: false`, `@world` returns "Command 'world' is not available."
- [ ] With `telegram.enabled: true` plus a real bot token + chat ids, world chat round-trips between game and Telegram. Bot privacy mode must be off.

## Notes for the implementer

- **Class-name collisions during the migration window.** Tasks 4–7 create new classes in the `telegram` package while the IRC versions still exist in the `irc` package. Both have identical class names (`WorldChannelMap`, `RateLimiter`, etc.). They're in different packages so this compiles, but watch the test runner's `-Dtest=...` patterns — qualify by package (`net.server.chat.telegram.WorldChannelMapTest`) when running a single test. Task 16 removes the IRC versions and the ambiguity goes away.
- **pengrad's apiUrl quirk.** The Bot API URL convention is `https://api.telegram.org/bot{token}/{method}`. pengrad's `Builder.apiUrl(...)` expects the URL up to and including `/bot` (i.e. `https://api.telegram.org/bot`). For the fake API, `TelegramClient` appends `/bot` to whatever the user passes via the Builder. Tests that hit the fake pass `apiUrl(fake.urlBase())` (e.g. `http://127.0.0.1:54321`) and the client builds `http://127.0.0.1:54321/bot{token}/getUpdates`. The `FakeTelegramApi` route handler matches on `endsWith("/getUpdates")` so the prefix doesn't matter.
- **Test threading.** Two integration tests (`TelegramClientTest.inboundTextMessage_invokesListener` and `TelegramBridgeServiceTest.inboundTextDeliveredAsLocalBroadcast`) poll for an outcome with a deadline + `Thread.sleep(20)`. Polling timeout is 1s in tests; expect ~1.5–4s wall time per test. CI runner speed should be fine.
- **CommandsExecutor static state.** Each `TelegramBridgeServiceTest` test that calls `start()` registers `@world`. The `@AfterEach` calls `unregisterCommand("world")` to keep tests independent. Don't remove that; it bites if you reorder.
