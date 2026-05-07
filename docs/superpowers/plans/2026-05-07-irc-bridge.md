# Cosmic IRC Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bidirectional bridge between a new in-game `@world` chat surface and one IRC channel per Cosmic world, gated on `irc.enabled` and off by default.

**Architecture:** Cosmic dials out to one external IRC network as a single connection (hand-rolled minimal client), joins a configured channel per world, and ferries `PRIVMSG` lines in both directions. In-game players send via `@world <text>`; IRC traffic appears in-game as a `serverNotice(6, "[IRC]nick: text")` lightblue chat-log line. Two threads per connection (read + writer) with a bounded outbound queue and capped exponential reconnect backoff.

**Tech Stack:** Java 21, JUnit 5, existing project conventions (hand-rolled fakes, no Mockito, SLF4J via Logger), `tools.PacketCreator.serverNotice`, `net.server.Server.broadcastMessage`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-irc-bridge-design.md`

---

## File map

**New main:**
- `src/main/java/config/IrcConfigYaml.java`
- `src/main/java/net/server/chat/irc/IrcConfig.java`
- `src/main/java/net/server/chat/irc/WorldChannelMap.java`
- `src/main/java/net/server/chat/irc/IrcMessage.java`
- `src/main/java/net/server/chat/irc/IrcLineParser.java`
- `src/main/java/net/server/chat/irc/RateLimiter.java`
- `src/main/java/net/server/chat/irc/IrcSender.java`
- `src/main/java/net/server/chat/irc/WorldBroadcaster.java`
- `src/main/java/net/server/chat/irc/WorldChatService.java`
- `src/main/java/net/server/chat/irc/IrcConnection.java`
- `src/main/java/net/server/chat/irc/IrcBridgeService.java`
- `src/main/java/client/command/commands/gm0/WorldCommand.java`

**New tests:**
- `src/test/java/net/server/chat/irc/IrcConfigTest.java`
- `src/test/java/net/server/chat/irc/WorldChannelMapTest.java`
- `src/test/java/net/server/chat/irc/IrcLineParserTest.java`
- `src/test/java/net/server/chat/irc/RateLimiterTest.java`
- `src/test/java/net/server/chat/irc/WorldChatServiceTest.java`
- `src/test/java/net/server/chat/irc/WorldCommandTest.java`
- `src/test/java/net/server/chat/irc/IrcConnectionTest.java`
- `src/test/java/net/server/chat/irc/IrcBridgeServiceTest.java`
- `src/test/java/net/server/chat/irc/FakeIrcServer.java` *(test harness, not a test class)*

**Modified:**
- `src/main/java/config/YamlConfig.java`
- `src/main/java/client/command/CommandsExecutor.java`
- `src/main/java/net/server/Server.java`
- `config.yaml`
- `README.md`

---

## Task 1: IrcConfigYaml raw POJO + YamlConfig wiring

**Files:**
- Create: `src/main/java/config/IrcConfigYaml.java`
- Modify: `src/main/java/config/YamlConfig.java:19`

- [ ] **Step 1: Create `IrcConfigYaml.java`**

```java
package config;

import java.util.List;
import java.util.Map;

public class IrcConfigYaml {
    public boolean enabled;
    public String server;
    public int port;
    public boolean tls;
    public String nick;
    public String user;
    public String realname;
    public String password;
    public boolean allow_plaintext_password;
    public Map<Integer, String> channels;
    public int outbound_queue_max;
    public int worldchat_rate_per_minute;
    public int worldchat_max_length;
    public List<Integer> reconnect_backoff_seconds;
}
```

- [ ] **Step 2: Add `irc` field to `YamlConfig`**

Edit `src/main/java/config/YamlConfig.java` and add the field after `mcp`:

```java
public McpConfigYaml mcp;
public IrcConfigYaml irc;
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS, no compile errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/config/IrcConfigYaml.java src/main/java/config/YamlConfig.java
git commit -m "Add IrcConfigYaml POJO and wire into YamlConfig"
```

---

## Task 2: IrcConfig validated wrapper

**Files:**
- Create: `src/main/java/net/server/chat/irc/IrcConfig.java`
- Test: `src/test/java/net/server/chat/irc/IrcConfigTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/server/chat/irc/IrcConfigTest.java`:

```java
package net.server.chat.irc;

import config.IrcConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrcConfigTest {

    @Test
    void disabled_isValid_andHasNoChannels() {
        IrcConfigYaml yaml = new IrcConfigYaml();
        yaml.enabled = false;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.enabled());
        assertTrue(cfg.isValid());
    }

    @Test
    void enabled_requiresServer_nick_andAtLeastOneChannel() {
        IrcConfigYaml yaml = baseValid();
        yaml.server = "";
        IrcConfig cfg = IrcConfig.from(yaml);
        assertTrue(cfg.enabled());
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("server"));
    }

    @Test
    void enabled_rejectsEmptyChannels() {
        IrcConfigYaml yaml = baseValid();
        yaml.channels = Map.of();
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("channels"));
    }

    @Test
    void enabled_rejectsPortOutOfRange() {
        IrcConfigYaml yaml = baseValid();
        yaml.port = 0;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("port"));
    }

    @Test
    void enabled_rejectsPlaintextPasswordWithoutEscapeHatch() {
        IrcConfigYaml yaml = baseValid();
        yaml.tls = false;
        yaml.password = "secret";
        yaml.allow_plaintext_password = false;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertFalse(cfg.isValid());
        assertTrue(cfg.validationError().contains("plaintext"));
    }

    @Test
    void enabled_acceptsPlaintextPasswordWithEscapeHatch() {
        IrcConfigYaml yaml = baseValid();
        yaml.tls = false;
        yaml.password = "secret";
        yaml.allow_plaintext_password = true;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertTrue(cfg.isValid());
    }

    @Test
    void backoffDefaults_ifMissing() {
        IrcConfigYaml yaml = baseValid();
        yaml.reconnect_backoff_seconds = null;
        IrcConfig cfg = IrcConfig.from(yaml);
        assertEquals(List.of(5, 10, 30, 60, 60), cfg.reconnectBackoffSeconds());
    }

    private IrcConfigYaml baseValid() {
        IrcConfigYaml y = new IrcConfigYaml();
        y.enabled = true;
        y.server = "irc.libera.chat";
        y.port = 6697;
        y.tls = true;
        y.nick = "cosmic-bridge";
        y.user = "cosmic";
        y.realname = "Cosmic Chat Bridge";
        y.password = "";
        y.allow_plaintext_password = false;
        y.channels = Map.of(0, "#cosmic-scania");
        y.outbound_queue_max = 1000;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.reconnect_backoff_seconds = List.of(5, 10, 30, 60, 60);
        return y;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=IrcConfigTest test`
Expected: FAIL with "cannot find symbol IrcConfig".

- [ ] **Step 3: Create `IrcConfig.java`**

```java
package net.server.chat.irc;

import config.IrcConfigYaml;

import java.util.List;
import java.util.Map;

public final class IrcConfig {

    private static final List<Integer> DEFAULT_BACKOFF = List.of(5, 10, 30, 60, 60);
    private static final int DEFAULT_QUEUE_MAX = 1000;
    private static final int DEFAULT_RATE_PER_MINUTE = 6;
    private static final int DEFAULT_MAX_LENGTH = 200;

    private final boolean enabled;
    private final String server;
    private final int port;
    private final boolean tls;
    private final String nick;
    private final String user;
    private final String realname;
    private final String password;
    private final boolean allowPlaintextPassword;
    private final Map<Integer, String> channels;
    private final int outboundQueueMax;
    private final int rateLimitPerMinute;
    private final int maxLength;
    private final List<Integer> backoff;
    private final String validationError;

    private IrcConfig(boolean enabled, String server, int port, boolean tls,
                      String nick, String user, String realname,
                      String password, boolean allowPlaintextPassword,
                      Map<Integer, String> channels,
                      int outboundQueueMax, int rateLimitPerMinute, int maxLength,
                      List<Integer> backoff, String validationError) {
        this.enabled = enabled;
        this.server = server;
        this.port = port;
        this.tls = tls;
        this.nick = nick;
        this.user = user;
        this.realname = realname;
        this.password = password;
        this.allowPlaintextPassword = allowPlaintextPassword;
        this.channels = channels;
        this.outboundQueueMax = outboundQueueMax;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.maxLength = maxLength;
        this.backoff = backoff;
        this.validationError = validationError;
    }

    public static IrcConfig from(IrcConfigYaml y) {
        if (y == null || !y.enabled) {
            return new IrcConfig(false, "", 0, false, "", "", "", "", false,
                    Map.of(), DEFAULT_QUEUE_MAX, DEFAULT_RATE_PER_MINUTE, DEFAULT_MAX_LENGTH,
                    DEFAULT_BACKOFF, null);
        }

        String err = validate(y);

        Map<Integer, String> channels = y.channels == null ? Map.of() : Map.copyOf(y.channels);
        List<Integer> backoff = (y.reconnect_backoff_seconds == null || y.reconnect_backoff_seconds.isEmpty())
                ? DEFAULT_BACKOFF
                : List.copyOf(y.reconnect_backoff_seconds);
        int queueMax = y.outbound_queue_max > 0 ? y.outbound_queue_max : DEFAULT_QUEUE_MAX;
        int ratePerMinute = y.worldchat_rate_per_minute > 0 ? y.worldchat_rate_per_minute : DEFAULT_RATE_PER_MINUTE;
        int maxLen = y.worldchat_max_length > 0 ? y.worldchat_max_length : DEFAULT_MAX_LENGTH;

        return new IrcConfig(true,
                nullToEmpty(y.server), y.port, y.tls,
                nullToEmpty(y.nick), nullToEmpty(y.user), nullToEmpty(y.realname),
                nullToEmpty(y.password), y.allow_plaintext_password,
                channels, queueMax, ratePerMinute, maxLen, backoff, err);
    }

    private static String validate(IrcConfigYaml y) {
        if (y.server == null || y.server.isBlank()) return "server is required";
        if (y.nick == null || y.nick.isBlank()) return "nick is required";
        if (y.port < 1 || y.port > 65535) return "port out of range";
        if (y.channels == null || y.channels.isEmpty()) return "channels must not be empty";
        if (y.password != null && !y.password.isEmpty() && !y.tls && !y.allow_plaintext_password) {
            return "plaintext password requires allow_plaintext_password: true";
        }
        return null;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public boolean enabled() { return enabled; }
    public boolean isValid() { return enabled ? validationError == null : true; }
    public String validationError() { return validationError; }
    public String server() { return server; }
    public int port() { return port; }
    public boolean tls() { return tls; }
    public String nick() { return nick; }
    public String user() { return user; }
    public String realname() { return realname; }
    public String password() { return password; }
    public Map<Integer, String> channels() { return channels; }
    public int outboundQueueMax() { return outboundQueueMax; }
    public int rateLimitPerMinute() { return rateLimitPerMinute; }
    public int maxLength() { return maxLength; }
    public List<Integer> reconnectBackoffSeconds() { return backoff; }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=IrcConfigTest test`
Expected: BUILD SUCCESS, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcConfig.java src/test/java/net/server/chat/irc/IrcConfigTest.java
git commit -m "Add IrcConfig with validation"
```

---

## Task 3: WorldChannelMap

**Files:**
- Create: `src/main/java/net/server/chat/irc/WorldChannelMap.java`
- Test: `src/test/java/net/server/chat/irc/WorldChannelMapTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorldChannelMapTest {

    @Test
    void resolvesWorldToChannelAndBack() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(
                0, "#cosmic-scania",
                1, "#cosmic-bera"
        ));
        assertEquals("#cosmic-scania", map.channel(0).orElseThrow());
        assertEquals(1, map.world("#cosmic-bera").orElseThrow());
    }

    @Test
    void channelLookupIsCaseInsensitive() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#Cosmic-Scania"));
        assertEquals(0, map.world("#cosmic-scania").orElseThrow());
        assertEquals(0, map.world("#COSMIC-SCANIA").orElseThrow());
    }

    @Test
    void unmappedReturnsEmpty() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        assertTrue(map.channel(99).isEmpty());
        assertTrue(map.world("#nope").isEmpty());
    }

    @Test
    void allChannels_returnsAllRegisteredChannels() {
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a", 1, "#b"));
        assertEquals(2, map.allChannels().size());
        assertTrue(map.allChannels().contains("#a"));
        assertTrue(map.allChannels().contains("#b"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=WorldChannelMapTest test`
Expected: FAIL with "cannot find symbol WorldChannelMap".

- [ ] **Step 3: Implement**

```java
package net.server.chat.irc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WorldChannelMap {

    private final Map<Integer, String> worldToChannel;
    private final Map<String, Integer> channelToWorld;

    private WorldChannelMap(Map<Integer, String> worldToChannel,
                            Map<String, Integer> channelToWorld) {
        this.worldToChannel = worldToChannel;
        this.channelToWorld = channelToWorld;
    }

    public static WorldChannelMap of(Map<Integer, String> raw) {
        Map<Integer, String> w = new HashMap<>();
        Map<String, Integer> c = new HashMap<>();
        for (Map.Entry<Integer, String> e : raw.entrySet()) {
            w.put(e.getKey(), e.getValue());
            c.put(e.getValue().toLowerCase(Locale.ROOT), e.getKey());
        }
        return new WorldChannelMap(Map.copyOf(w), Map.copyOf(c));
    }

    public Optional<String> channel(int worldId) {
        return Optional.ofNullable(worldToChannel.get(worldId));
    }

    public Optional<Integer> world(String channel) {
        return Optional.ofNullable(channelToWorld.get(channel.toLowerCase(Locale.ROOT)));
    }

    public Collection<String> allChannels() {
        return worldToChannel.values();
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=WorldChannelMapTest test`
Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/WorldChannelMap.java src/test/java/net/server/chat/irc/WorldChannelMapTest.java
git commit -m "Add WorldChannelMap (bidirectional, case-insensitive)"
```

---

## Task 4: IrcMessage record + IrcLineParser

**Files:**
- Create: `src/main/java/net/server/chat/irc/IrcMessage.java`
- Create: `src/main/java/net/server/chat/irc/IrcLineParser.java`
- Test: `src/test/java/net/server/chat/irc/IrcLineParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IrcLineParserTest {

    @Test
    void privmsg_simple() {
        Optional<IrcMessage> msg = IrcLineParser.parse(":nick!u@h PRIVMSG #chan :hello world");
        assertTrue(msg.isPresent());
        IrcMessage m = msg.orElseThrow();
        assertEquals("nick", m.nick());
        assertEquals("PRIVMSG", m.command());
        assertEquals("#chan", m.params().get(0));
        assertEquals("hello world", m.trailing());
    }

    @Test
    void ping_noPrefix() {
        IrcMessage m = IrcLineParser.parse("PING :foo").orElseThrow();
        assertEquals("", m.nick());
        assertEquals("PING", m.command());
        assertEquals("foo", m.trailing());
    }

    @Test
    void numericReply_001() {
        IrcMessage m = IrcLineParser.parse(":server.example 001 cosmic-bridge :Welcome to the network").orElseThrow();
        assertEquals("001", m.command());
        assertEquals("cosmic-bridge", m.params().get(0));
        assertEquals("Welcome to the network", m.trailing());
    }

    @Test
    void empty_returnsEmpty() {
        assertTrue(IrcLineParser.parse("").isEmpty());
        assertTrue(IrcLineParser.parse("   ").isEmpty());
    }

    @Test
    void malformed_singleColon_returnsEmpty() {
        assertTrue(IrcLineParser.parse(":").isEmpty());
    }

    @Test
    void privmsg_noTrailing() {
        IrcMessage m = IrcLineParser.parse(":a!b@c PRIVMSG #chan").orElseThrow();
        assertEquals("PRIVMSG", m.command());
        assertEquals("", m.trailing());
    }

    @Test
    void nickFromPrefix_handlesUserHostMissing() {
        IrcMessage m = IrcLineParser.parse(":nick PRIVMSG #chan :hi").orElseThrow();
        assertEquals("nick", m.nick());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=IrcLineParserTest test`
Expected: FAIL with "cannot find symbol IrcMessage".

- [ ] **Step 3: Create `IrcMessage` record**

```java
package net.server.chat.irc;

import java.util.List;

public record IrcMessage(String prefix, String nick, String command,
                         List<String> params, String trailing) {
}
```

- [ ] **Step 4: Create `IrcLineParser`**

```java
package net.server.chat.irc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IrcLineParser {

    private IrcLineParser() {}

    public static Optional<IrcMessage> parse(String raw) {
        if (raw == null) return Optional.empty();
        String line = raw.strip();
        if (line.isEmpty()) return Optional.empty();

        String prefix = "";
        if (line.startsWith(":")) {
            int sp = line.indexOf(' ');
            if (sp <= 1) return Optional.empty();
            prefix = line.substring(1, sp);
            line = line.substring(sp + 1).stripLeading();
            if (line.isEmpty()) return Optional.empty();
        }

        String trailing = "";
        int trailingIdx = line.indexOf(" :");
        if (trailingIdx >= 0) {
            trailing = line.substring(trailingIdx + 2);
            line = line.substring(0, trailingIdx);
        }

        String[] tokens = line.split(" +");
        if (tokens.length == 0 || tokens[0].isEmpty()) return Optional.empty();
        String command = tokens[0];
        List<String> params = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) params.add(tokens[i]);

        String nick = nickFromPrefix(prefix);
        return Optional.of(new IrcMessage(prefix, nick, command, List.copyOf(params), trailing));
    }

    private static String nickFromPrefix(String prefix) {
        if (prefix.isEmpty()) return "";
        int bang = prefix.indexOf('!');
        if (bang < 0) {
            int dot = prefix.indexOf('.');
            if (dot >= 0) return "";   // looks like a server name
            return prefix;
        }
        return prefix.substring(0, bang);
    }
}
```

- [ ] **Step 5: Run test, expect pass**

Run: `./mvnw -q -Dtest=IrcLineParserTest test`
Expected: BUILD SUCCESS, 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcMessage.java src/main/java/net/server/chat/irc/IrcLineParser.java src/test/java/net/server/chat/irc/IrcLineParserTest.java
git commit -m "Add IrcLineParser for the protocol subset we need"
```

---

## Task 5: RateLimiter

**Files:**
- Create: `src/main/java/net/server/chat/irc/RateLimiter.java`
- Test: `src/test/java/net/server/chat/irc/RateLimiterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

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
        RateLimiter rl = new RateLimiter(6, clock);  // 6 per minute
        for (int i = 0; i < 6; i++) assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void refillsOverTime() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(6, clock);
        for (int i = 0; i < 6; i++) rl.tryAcquire(42);
        assertFalse(rl.tryAcquire(42));
        clock.advance(10_000);   // 10s = 1 token at 6/min
        assertTrue(rl.tryAcquire(42));
        assertFalse(rl.tryAcquire(42));
    }

    @Test
    void perCharacterIsolation() {
        ManualClock clock = new ManualClock(0);
        RateLimiter rl = new RateLimiter(1, clock);
        assertTrue(rl.tryAcquire(1));
        assertFalse(rl.tryAcquire(1));
        assertTrue(rl.tryAcquire(2));   // different char
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

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=RateLimiterTest test`
Expected: FAIL with "cannot find symbol RateLimiter".

- [ ] **Step 3: Implement**

```java
package net.server.chat.irc;

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

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=RateLimiterTest test`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/RateLimiter.java src/test/java/net/server/chat/irc/RateLimiterTest.java
git commit -m "Add RateLimiter (per-character token bucket)"
```

---

## Task 6: IrcSender + WorldBroadcaster interfaces

**Files:**
- Create: `src/main/java/net/server/chat/irc/IrcSender.java`
- Create: `src/main/java/net/server/chat/irc/WorldBroadcaster.java`

- [ ] **Step 1: Create `IrcSender` interface**

```java
package net.server.chat.irc;

public interface IrcSender {
    /** Returns true if the line was queued; false if dropped (queue full / not connected). */
    boolean enqueue(String rawIrcLine);

    /** Current bot nick, used for echo-loop suppression. */
    String currentNick();
}
```

- [ ] **Step 2: Create `WorldBroadcaster` interface**

```java
package net.server.chat.irc;

import tools.Packet;

@FunctionalInterface
public interface WorldBroadcaster {
    void broadcast(int worldId, Packet packet);
}
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcSender.java src/main/java/net/server/chat/irc/WorldBroadcaster.java
git commit -m "Add IrcSender and WorldBroadcaster interfaces"
```

---

## Task 7: WorldChatService

**Files:**
- Create: `src/main/java/net/server/chat/irc/WorldChatService.java`
- Test: `src/test/java/net/server/chat/irc/WorldChatServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.Test;
import tools.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldChatServiceTest {

    @Test
    void send_fansOutToBothLocalAndIrc() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(0, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
        assertEquals(1, sender.lines.size());
        assertEquals("PRIVMSG #cosmic-scania :Alice hi", sender.lines.get(0));
    }

    @Test
    void send_unmappedWorld_skipsIrcButStillSelfLoops() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.send(99, "Alice", "hi");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, sender.lines.size());
    }

    @Test
    void deliverFromIrc_broadcastsLightblueServerNoticeToWorld() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#cosmic-scania"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromIrc(0, "ircnick", "hello");

        assertEquals(1, bc.broadcasts.size());
        assertEquals(0, bc.broadcasts.get(0).worldId);
    }

    @Test
    void deliverFromIrc_dropsEchoFromOwnNick() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 200);

        svc.deliverFromIrc(0, "Cosmic-Bridge", "loop?");

        assertEquals(0, bc.broadcasts.size());
    }

    @Test
    void deliverFromIrc_stripsControlCharsAndTruncates() {
        FakeBroadcaster bc = new FakeBroadcaster();
        FakeSender sender = new FakeSender("cosmic-bridge");
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, sender, bc, 5);

        svc.deliverFromIrc(0, "n", "ab\u0001cdef\u0002gh");
        // control chars stripped → "abcdefgh", truncated to 5 → "abcde…"
        assertEquals(1, bc.broadcasts.size());
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
        @Override public void broadcast(int worldId, Packet p) {
            broadcasts.add(new Bcast(worldId, p));
        }
    }

    record Bcast(int worldId, Packet packet) {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=WorldChatServiceTest test`
Expected: FAIL with "cannot find symbol WorldChatService".

- [ ] **Step 3: Implement**

```java
package net.server.chat.irc;

import tools.PacketCreator;

public final class WorldChatService {

    private static final int LIGHTBLUE_NOTICE = 6;

    private final WorldChannelMap channels;
    private final IrcSender sender;
    private final WorldBroadcaster broadcaster;
    private final int maxLength;

    public WorldChatService(WorldChannelMap channels, IrcSender sender,
                            WorldBroadcaster broadcaster, int maxLength) {
        this.channels = channels;
        this.sender = sender;
        this.broadcaster = broadcaster;
        this.maxLength = maxLength;
    }

    public void send(int worldId, String charName, String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, charName + ": " + clean));

        channels.channel(worldId).ifPresent(chan ->
                sender.enqueue("PRIVMSG " + chan + " :" + charName + " " + clean));
    }

    public void deliverFromIrc(int worldId, String nick, String text) {
        if (sender.currentNick() != null && nick.equalsIgnoreCase(sender.currentNick())) return;
        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        broadcaster.broadcast(worldId,
                PacketCreator.serverNotice(LIGHTBLUE_NOTICE, "[IRC]" + nick + ": " + clean));
    }

    private String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 || c == '\t') sb.append(c);
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

Run: `./mvnw -q -Dtest=WorldChatServiceTest test`
Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/WorldChatService.java src/test/java/net/server/chat/irc/WorldChatServiceTest.java
git commit -m "Add WorldChatService (game ↔ IRC fan-out)"
```

---

## Task 8: IrcBridgeService static accessor stub

**Files:**
- Create: `src/main/java/net/server/chat/irc/IrcBridgeService.java`

This is a *partial* implementation — just the static accessor that `WorldCommand` will use. The full lifecycle is built later (Task 12).

- [ ] **Step 1: Create stub**

```java
package net.server.chat.irc;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class IrcBridgeService {

    private static final AtomicReference<IrcBridgeService> INSTANCE = new AtomicReference<>();

    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    public IrcBridgeService(WorldChatService worldChat, RateLimiter rateLimiter) {
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<IrcBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(IrcBridgeService svc) {
        INSTANCE.set(svc);
    }

    public static void clearInstance() {
        INSTANCE.set(null);
    }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcBridgeService.java
git commit -m "Add IrcBridgeService static accessor stub"
```

---

## Task 9: WorldCommand

**Files:**
- Create: `src/main/java/client/command/commands/gm0/WorldCommand.java`
- Test: `src/test/java/net/server/chat/irc/WorldCommandTest.java`

The command uses `IrcBridgeService.instance()`; if absent, drops silently. We test only the service-routing logic in unit tests — full command-execute coverage requires a `Client` which is heavyweight. Instead, the test extracts the routing into a static helper `WorldCommand.deliver(...)` that takes the dependencies explicitly.

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import client.command.commands.gm0.WorldCommand;
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
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
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
        WorldChannelMap map = WorldChannelMap.of(Map.of(0, "#a"));
        WorldChatService svc = new WorldChatService(map, rec, rec, 200);
        RateLimiter rl = new RateLimiter(10, fixedClock());

        WorldCommand.deliver(svc, rl, 0, 42, "Alice", "   ");

        assertEquals(0, rec.broadcasts.size());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC);
    }

    static final class FakeRecorder implements IrcSender, WorldBroadcaster {
        final List<String> lines = new ArrayList<>();
        final List<Integer> broadcasts = new ArrayList<>();
        @Override public boolean enqueue(String l) { lines.add(l); return true; }
        @Override public String currentNick() { return "bot"; }
        @Override public void broadcast(int w, tools.Packet p) { broadcasts.add(w); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=WorldCommandTest test`
Expected: FAIL with "cannot find symbol WorldCommand".

- [ ] **Step 3: Implement**

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
            String text = String.join(" ", params).strip();
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

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=WorldCommandTest test`
Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/client/command/commands/gm0/WorldCommand.java src/test/java/net/server/chat/irc/WorldCommandTest.java
git commit -m "Add @world command with rate-limit routing"
```

---

## Task 10: Public command-registration API on CommandsExecutor

**Files:**
- Modify: `src/main/java/client/command/CommandsExecutor.java`

We need a way for `IrcBridgeService.start()` to register `WorldCommand` *after* the executor singleton has been built (so the command only exists when the bridge is actually running).

- [ ] **Step 1: Add public registration method**

In `src/main/java/client/command/CommandsExecutor.java`, after the existing `addCommand(String, int, Class)` private method (around line 346), add:

```java
public boolean registerLv0Command(String name, Class<? extends Command> commandClass) {
    if (registeredCommands.containsKey(name.toLowerCase())) return false;
    addCommand(name, 0, commandClass);
    return true;
}

public void unregisterCommand(String name) {
    registeredCommands.remove(name.toLowerCase());
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/client/command/CommandsExecutor.java
git commit -m "Expose runtime command registration on CommandsExecutor"
```

---

## Task 11: FakeIrcServer test harness

**Files:**
- Create: `src/test/java/net/server/chat/irc/FakeIrcServer.java`

Not a test class — a reusable in-process fake the next two tasks use. No test of its own; smoke-tested through the IrcConnection tests.

- [ ] **Step 1: Implement**

```java
package net.server.chat.irc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class FakeIrcServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptThread;
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private volatile Socket client;
    private volatile PrintWriter out;
    private volatile boolean stopped = false;

    public FakeIrcServer() throws IOException {
        this.server = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "FakeIrcServer-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int port() { return server.getLocalPort(); }

    public String takeLine(long timeoutMs) throws InterruptedException {
        return received.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public List<String> drain() {
        List<String> all = new ArrayList<>();
        received.drainTo(all);
        return all;
    }

    public void send(String line) {
        PrintWriter w = out;
        if (w != null) {
            w.print(line + "\r\n");
            w.flush();
        }
    }

    public void waitForClient(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (client == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    public void disconnectClient() throws IOException {
        Socket c = client;
        if (c != null) c.close();
        client = null;
    }

    @Override public void close() throws IOException {
        stopped = true;
        try { server.close(); } catch (IOException ignored) {}
        if (client != null) try { client.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = server.accept();
                client = s;
                out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    received.offer(line);
                }
            } catch (IOException e) {
                if (!stopped) {
                    // socket closed by test or by client; loop will exit if stopped
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/net/server/chat/irc/FakeIrcServer.java
git commit -m "Add FakeIrcServer test harness"
```

---

## Task 12: IrcConnection

**Files:**
- Create: `src/main/java/net/server/chat/irc/IrcConnection.java`
- Test: `src/test/java/net/server/chat/irc/IrcConnectionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.server.chat.irc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrcConnectionTest {

    private FakeIrcServer fake;
    private IrcConnection conn;

    @AfterEach
    void teardown() throws Exception {
        if (conn != null) conn.stop(2000);
        if (fake != null) fake.close();
    }

    @Test
    void onConnect_sendsNickAndUserAndJoinsAllChannels() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a", "#b"));

        conn.start();

        assertEquals("NICK cosmic-bridge", takeNonPing());
        assertEquals("USER cosmic 0 * :Cosmic Test", takeNonPing());
        // Many real servers wait for 001 before JOIN; for the test we send 001 to unblock JOIN.
        fake.send(":server 001 cosmic-bridge :Welcome");
        assertEquals("JOIN #a", takeNonPing());
        assertEquals("JOIN #b", takeNonPing());
    }

    @Test
    void incomingPing_isAnsweredWithPong() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a"));
        conn.start();
        drainRegistration();

        fake.send("PING :servername");
        assertEquals("PONG :servername", takeNonPing());
    }

    @Test
    void incomingPrivmsg_invokesListener() throws Exception {
        fake = new FakeIrcServer();
        java.util.concurrent.atomic.AtomicReference<IrcMessage> got = new java.util.concurrent.atomic.AtomicReference<>();
        conn = new IrcConnection.Builder()
                .host("127.0.0.1").port(fake.port()).tls(false)
                .nick("cosmic-bridge").user("cosmic").realname("Cosmic Test")
                .channels(List.of("#a")).queueMax(1000)
                .backoffSeconds(List.of(0)).onMessage(got::set).build();
        conn.start();
        drainRegistration();

        fake.send(":nick!u@h PRIVMSG #a :hello");
        // Spin until the listener received the message
        long deadline = System.currentTimeMillis() + 1500;
        while (got.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertNotNull(got.get());
        assertEquals("PRIVMSG", got.get().command());
    }

    @Test
    void enqueueWritesToSocket() throws Exception {
        fake = new FakeIrcServer();
        conn = newConnection(fake.port(), List.of("#a"));
        conn.start();
        drainRegistration();

        assertTrue(conn.enqueue("PRIVMSG #a :ping from us"));
        assertEquals("PRIVMSG #a :ping from us", takeNonPing());
    }

    private IrcConnection newConnection(int port, List<String> channels) {
        return new IrcConnection.Builder()
                .host("127.0.0.1").port(port).tls(false)
                .nick("cosmic-bridge").user("cosmic").realname("Cosmic Test")
                .channels(channels).queueMax(1000)
                .backoffSeconds(List.of(0)).onMessage(m -> {})
                .build();
    }

    private String takeNonPing() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            String line = fake.takeLine(1500);
            if (line == null) return null;
            if (line.startsWith("PING ") || line.startsWith("PONG ")) continue;
            return line;
        }
        return null;
    }

    private void drainRegistration() throws Exception {
        // NICK and USER lines
        fake.takeLine(1500);
        fake.takeLine(1500);
        fake.send(":server 001 cosmic-bridge :Welcome");
        // JOIN lines
        fake.takeLine(1500);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=IrcConnectionTest test`
Expected: FAIL with "cannot find symbol IrcConnection".

- [ ] **Step 3: Implement**

```java
package net.server.chat.irc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class IrcConnection implements IrcSender {

    private static final Logger log = LoggerFactory.getLogger(IrcConnection.class);

    private final String host;
    private final int port;
    private final boolean tls;
    private final String nickInitial;
    private final String user;
    private final String realname;
    private final String password;
    private final List<String> channels;
    private final int queueMax;
    private final List<Integer> backoffSeconds;
    private final Consumer<IrcMessage> onMessage;

    private final LinkedBlockingQueue<String> outbox;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentNick = new AtomicReference<>("");
    private volatile Thread readThread;
    private volatile Thread writeThread;
    private volatile Socket socket;
    private volatile PrintWriter writer;
    private volatile boolean registered = false;

    private IrcConnection(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.tls = b.tls;
        this.nickInitial = b.nick;
        this.currentNick.set(b.nick);
        this.user = b.user;
        this.realname = b.realname;
        this.password = b.password == null ? "" : b.password;
        this.channels = List.copyOf(b.channels);
        this.queueMax = b.queueMax;
        this.backoffSeconds = b.backoffSeconds.isEmpty() ? List.of(5, 10, 30, 60, 60) : List.copyOf(b.backoffSeconds);
        this.onMessage = b.onMessage == null ? m -> {} : b.onMessage;
        this.outbox = new LinkedBlockingQueue<>(queueMax);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        readThread = new Thread(this::readLoop, "irc-read");
        readThread.setDaemon(true);
        writeThread = new Thread(this::writeLoop, "irc-write");
        writeThread.setDaemon(true);
        readThread.start();
        writeThread.start();
    }

    public void stop(long timeoutMs) {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (writer != null) {
                writer.print("QUIT :Cosmic shutting down\r\n");
                writer.flush();
            }
        } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try {
            if (readThread != null) readThread.join(timeoutMs / 2);
            if (writeThread != null) writeThread.join(timeoutMs / 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public boolean enqueue(String rawIrcLine) {
        if (!running.get()) return false;
        return outbox.offer(rawIrcLine);
    }

    @Override public String currentNick() { return currentNick.get(); }

    private void readLoop() {
        int attempt = 0;
        while (running.get()) {
            try {
                connectAndRegister();
                attempt = 0;
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Exception e) {
                if (running.get()) log.warn("IRC read loop error: {}", e.toString());
            } finally {
                registered = false;
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            }
            if (!running.get()) break;
            int wait = backoffSeconds.get(Math.min(attempt, backoffSeconds.size() - 1));
            attempt++;
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                String line = outbox.poll(250, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                PrintWriter w = writer;
                if (w == null || !registered) continue;
                w.print(line + "\r\n");
                w.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) log.warn("IRC write loop error: {}", e.toString());
            }
        }
    }

    private void connectAndRegister() throws IOException {
        Socket s = tls
                ? SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
        this.socket = s;
        this.writer = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8);
        if (!password.isEmpty()) write("PASS " + password);
        write("NICK " + nickInitial);
        write("USER " + user + " 0 * :" + realname);
        currentNick.set(nickInitial);
    }

    private void handleLine(String raw) {
        IrcLineParser.parse(raw).ifPresent(m -> {
            switch (m.command()) {
                case "PING" -> write("PONG :" + m.trailing());
                case "001" -> {
                    registered = true;
                    for (String ch : channels) write("JOIN " + ch);
                }
                case "433" -> {
                    String fallback = currentNick.get() + "_";
                    currentNick.set(fallback);
                    write("NICK " + fallback);
                }
                default -> {}
            }
            try { onMessage.accept(m); }
            catch (Exception e) { log.debug("listener threw on {}: {}", m.command(), e.toString()); }
        });
    }

    private void write(String line) {
        PrintWriter w = writer;
        if (w == null) return;
        w.print(line + "\r\n");
        w.flush();
    }

    public static final class Builder {
        private String host = "";
        private int port = 6697;
        private boolean tls = true;
        private String nick = "";
        private String user = "";
        private String realname = "";
        private String password = "";
        private List<String> channels = List.of();
        private int queueMax = 1000;
        private List<Integer> backoffSeconds = List.of();
        private Consumer<IrcMessage> onMessage = m -> {};

        public Builder host(String v) { this.host = v; return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder tls(boolean v) { this.tls = v; return this; }
        public Builder nick(String v) { this.nick = v; return this; }
        public Builder user(String v) { this.user = v; return this; }
        public Builder realname(String v) { this.realname = v; return this; }
        public Builder password(String v) { this.password = v; return this; }
        public Builder channels(List<String> v) { this.channels = v; return this; }
        public Builder queueMax(int v) { this.queueMax = v; return this; }
        public Builder backoffSeconds(List<Integer> v) { this.backoffSeconds = v; return this; }
        public Builder onMessage(Consumer<IrcMessage> v) { this.onMessage = v; return this; }
        public IrcConnection build() { return new IrcConnection(this); }
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=IrcConnectionTest test`
Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcConnection.java src/test/java/net/server/chat/irc/IrcConnectionTest.java
git commit -m "Add IrcConnection (socket + reconnect + read/write threads)"
```

---

## Task 13: IrcBridgeService full lifecycle

**Files:**
- Modify: `src/main/java/net/server/chat/irc/IrcBridgeService.java`
- Test: `src/test/java/net/server/chat/irc/IrcBridgeServiceTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package net.server.chat.irc;

import client.command.CommandsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrcBridgeServiceTest {

    private FakeIrcServer fake;
    private IrcBridgeService svc;

    @AfterEach
    void teardown() throws Exception {
        if (svc != null) svc.stop(2000);
        if (fake != null) fake.close();
        IrcBridgeService.clearInstance();
        CommandsExecutor.getInstance().unregisterCommand("world");
    }

    @Test
    void start_dialsAndJoinsChannels_thenSendRoutesGameTextToIrc() throws Exception {
        fake = new FakeIrcServer();
        IrcConfigYamlSnapshot yaml = baseValid(fake.port());
        IrcConfig cfg = IrcConfig.from(toYaml(yaml));

        List<int[]> broadcasts = new ArrayList<>();
        WorldBroadcaster bc = (w, p) -> broadcasts.add(new int[]{w});

        svc = IrcBridgeService.start(cfg, bc, Clock.systemUTC());

        // simulate registered IRC server
        // (fake will accept; bridge sends NICK/USER; we send 001 then expect JOIN)
        fake.takeLine(2000);  // NICK
        fake.takeLine(2000);  // USER
        fake.send(":server 001 " + cfg.nick() + " :Welcome");
        String join = fake.takeLine(2000);
        assertEquals("JOIN #cosmic-test", join);

        // game player @world hi
        svc.worldChat().send(0, "Alice", "hi");
        String line = fake.takeLine(2000);
        assertEquals("PRIVMSG #cosmic-test :Alice hi", line);
        assertEquals(1, broadcasts.size());

        // inbound from IRC
        fake.send(":someone!u@h PRIVMSG #cosmic-test :hello back");
        long deadline = System.currentTimeMillis() + 1500;
        while (broadcasts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(2, broadcasts.size());
    }

    @Test
    void start_registersWorldCommand_andStopUnregistersIt() throws Exception {
        fake = new FakeIrcServer();
        IrcConfig cfg = IrcConfig.from(toYaml(baseValid(fake.port())));
        WorldBroadcaster bc = (w, p) -> {};

        svc = IrcBridgeService.start(cfg, bc, Clock.systemUTC());
        assertTrue(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));

        svc.stop(2000);
        svc = null;
        assertFalse(CommandsExecutor.getInstance().getRegisteredCommands().containsKey("world"));
    }

    record IrcConfigYamlSnapshot(int port) {}

    private IrcConfigYamlSnapshot baseValid(int port) {
        return new IrcConfigYamlSnapshot(port);
    }

    private config.IrcConfigYaml toYaml(IrcConfigYamlSnapshot snap) {
        config.IrcConfigYaml y = new config.IrcConfigYaml();
        y.enabled = true;
        y.server = "127.0.0.1";
        y.port = snap.port();
        y.tls = false;
        y.nick = "cosmic-bridge";
        y.user = "cosmic";
        y.realname = "Cosmic";
        y.password = "";
        y.allow_plaintext_password = false;
        y.channels = Map.of(0, "#cosmic-test");
        y.outbound_queue_max = 1000;
        y.worldchat_rate_per_minute = 6;
        y.worldchat_max_length = 200;
        y.reconnect_backoff_seconds = List.of(0);
        return y;
    }
}
```

This test references `CommandsExecutor.getInstance().getRegisteredCommands()` which already exists (the existing `getRegisteredCommands()` accessor at `CommandsExecutor.java:222`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=IrcBridgeServiceTest test`
Expected: FAIL — `IrcBridgeService.start(IrcConfig, WorldBroadcaster, Clock)` does not exist yet.

- [ ] **Step 3: Replace `IrcBridgeService` with full implementation**

Replace the contents of `src/main/java/net/server/chat/irc/IrcBridgeService.java` with:

```java
package net.server.chat.irc;

import client.command.CommandsExecutor;
import client.command.commands.gm0.WorldCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class IrcBridgeService {

    private static final Logger log = LoggerFactory.getLogger(IrcBridgeService.class);
    private static final AtomicReference<IrcBridgeService> INSTANCE = new AtomicReference<>();

    private final IrcConnection connection;
    private final WorldChatService worldChat;
    private final RateLimiter rateLimiter;

    private IrcBridgeService(IrcConnection connection, WorldChatService worldChat, RateLimiter rateLimiter) {
        this.connection = connection;
        this.worldChat = worldChat;
        this.rateLimiter = rateLimiter;
    }

    public WorldChatService worldChat() { return worldChat; }
    public RateLimiter rateLimiter() { return rateLimiter; }

    public static Optional<IrcBridgeService> instance() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static void setInstance(IrcBridgeService svc) { INSTANCE.set(svc); }
    public static void clearInstance() { INSTANCE.set(null); }

    public static IrcBridgeService start(IrcConfig cfg, WorldBroadcaster broadcaster, Clock clock) {
        if (!cfg.enabled() || !cfg.isValid()) {
            log.info("IRC bridge not started (enabled={}, valid={})", cfg.enabled(), cfg.isValid());
            return null;
        }

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

        RateLimiter rl = new RateLimiter(cfg.rateLimitPerMinute(), clock);
        IrcBridgeService svc = new IrcBridgeService(conn, chat, rl);
        setInstance(svc);

        CommandsExecutor.getInstance().registerLv0Command("world", WorldCommand.class);
        conn.start();
        log.info("IRC bridge started: {}:{} channels={}", cfg.server(), cfg.port(), channelList);
        return svc;
    }

    public void stop(long timeoutMs) {
        try {
            CommandsExecutor.getInstance().unregisterCommand("world");
        } catch (Exception e) {
            log.warn("failed to unregister @world: {}", e.toString());
        }
        connection.stop(timeoutMs);
        clearInstance();
        log.info("IRC bridge stopped");
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./mvnw -q -Dtest=IrcBridgeServiceTest test`
Expected: BUILD SUCCESS, 2 tests pass. (May need brief sleeps in test if registered=false races; the existing 2-second polling loops are sufficient.)

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS — confirm no other tests regressed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/server/chat/irc/IrcBridgeService.java src/test/java/net/server/chat/irc/IrcBridgeServiceTest.java
git commit -m "Wire IrcBridgeService lifecycle (connection + service + command)"
```

---

## Task 14: Server.init() boot block + shutdown hook

**Files:**
- Modify: `src/main/java/net/server/Server.java`

- [ ] **Step 1: Add the boot block after the MCP block**

In `src/main/java/net/server/Server.java`, locate the MCP boot block's closing catch (currently around line 1046):

```java
        } catch (Exception e) {
            log.warn("Failed to start MCP server (game server continuing)", e);
        }

        for (Channel ch : this.getAllChannels()) {
            ch.reloadEventScriptManager();
        }
```

Insert the IRC boot block between the MCP catch and the channel-reload loop:

```java
        } catch (Exception e) {
            log.warn("Failed to start MCP server (game server continuing)", e);
        }

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

        for (Channel ch : this.getAllChannels()) {
            ch.reloadEventScriptManager();
        }
```

- [ ] **Step 2: Add shutdown hook**

In `src/main/java/net/server/Server.java:2161` — `shutdownInternal`'s existing MCP shutdown is:

```java
if (mcpServer != null) {
    mcpServer.stop();
}
```

Insert the IRC shutdown immediately before that block:

```java
try {
    net.server.chat.irc.IrcBridgeService.instance().ifPresent(b -> b.stop(2000));
} catch (Exception e) {
    log.warn("IRC bridge shutdown error", e);
}
if (mcpServer != null) {
    mcpServer.stop();
}
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, all tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/Server.java
git commit -m "Wire IRC bridge into Server.init() and shutdown"
```

---

## Task 15: config.yaml block and README documentation

**Files:**
- Modify: `config.yaml`
- Modify: `README.md`

- [ ] **Step 1: Add `irc:` block to `config.yaml`**

Append to `config.yaml`, after the existing `mcp:` block:

```yaml
irc:
  enabled: false
  server: irc.libera.chat
  port: 6697
  tls: true
  nick: cosmic-bridge
  user: cosmic
  realname: Cosmic Chat Bridge
  password: ""
  allow_plaintext_password: false
  channels:
    0: "#cosmic-scania"
    1: "#cosmic-bera"
    2: "#cosmic-broa"
  outbound_queue_max: 1000
  worldchat_rate_per_minute: 6
  worldchat_max_length: 200
  reconnect_backoff_seconds: [5, 10, 30, 60, 60]
```

- [ ] **Step 2: Add README section**

Append to `README.md` after the existing "MCP admin tools (Slice 3)" section:

```markdown
#### IRC bridge

Cosmic can bridge a new world-wide chat surface bidirectionally to an IRC channel per world. Players use `@world <text>` in-game; IRC users in the matching channel see `<PlayerName> text`. Inbound IRC traffic appears in-game as a lightblue chat-log line: `[IRC]nick: text`. **Disabled by default.**

To enable, set `irc.enabled: true` in `config.yaml`, configure the IRC network host/nick, and map your worlds to IRC channels:

```yaml
irc:
  enabled: true
  server: irc.libera.chat
  port: 6697
  tls: true
  nick: cosmic-bridge
  channels:
    0: "#cosmic-scania"
```

Cosmic dials out as a single connection (relay-bot model — Cosmic is not an IRC server). The bridge tolerates IRC-side outages: in-game `@world` traffic still reaches local players via a self-loop while the bridge reconnects with capped exponential backoff. `@world` is rate-limited per character (default 6/min) and length-capped at 200 chars.

**Privacy note:** world chat is publicly observable on the configured IRC channel. Players who do not want their character name on a public IRC log should not type `@world`.
```

- [ ] **Step 3: Manual smoke-test instructions**

(Skip if no real IRC network is available.)

1. Set `irc.enabled: true` and point `irc.server` at an IRC network you control or a test ircd (e.g. `inspircd` in Docker on `localhost:6667`, `tls: false`).
2. Start Cosmic. Confirm the log line `IRC bridge started: <host>:<port> channels=[...]`.
3. Connect to the IRC network with any client, join `#cosmic-scania`. You should see Cosmic's bot present.
4. Log into the game, type `@world hello`. You should see `Alice: hello` appear in the IRC channel.
5. Speak in IRC: `friend: how's it going`. In the game, every player in Scania should see a lightblue line: `[IRC]friend: how's it going`.
6. Stop Cosmic; bot leaves with `QUIT :Cosmic shutting down`.

- [ ] **Step 4: Commit**

```bash
git add config.yaml README.md
git commit -m "Document IRC bridge in config and README"
```

---

## Verification checklist

After all tasks:

- [ ] `./mvnw test` is green.
- [ ] No new dependencies in `pom.xml` (sanity-check with `git diff master -- pom.xml` — should be empty).
- [ ] `tools/list` on the running server is unchanged (the bridge does not add MCP tools).
- [ ] With `irc.enabled: false`, the existing in-game chat surfaces are unchanged and `@world` returns "Command 'world' is not available."
- [ ] With `irc.enabled: true` and a reachable network, world chat round-trips to and from IRC.

## Notes for the implementer

- The pattern for SLF4J `Logger` matches existing code; do not switch to other logging frameworks.
- Tests follow the project's existing style: hand-rolled fakes in the same package, no Mockito, JUnit 5.
- All blocking calls in tests use a poll-with-deadline pattern (`while (...) Thread.sleep(10)` up to a deadline) rather than fixed sleeps.
- Reconnect timing in tests: `backoffSeconds: [0]` — the production default of `[5, 10, 30, 60, 60]` is replaced in every test fixture so tests run in milliseconds.
