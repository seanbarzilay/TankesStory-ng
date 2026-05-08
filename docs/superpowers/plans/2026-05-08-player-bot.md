# Player Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an in-process player-bot v1 (companion `FOLLOW` + grinder `GRIND`) per `docs/superpowers/specs/2026-05-08-player-bot-design.md`.

**Architecture:** A bot is a `Character` driven by a stub `BotClient` and a `BotBrain` that ticks on a shared scheduler. Two control surfaces: in-game `@bot` commands and MCP tools (`cosmic.bot.spawn|drive|list`). Disabled by default via `bots.enabled` in `config.yaml`.

**Tech Stack:** Java 21, Maven (run via `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn ...`), JUnit 5, Mockito, SLF4J, Jackson (for MCP tool I/O).

---

## Conventions

- All `mvn` commands run inside the maven container — no JDK on the host. Repo root is mounted at `/build`.
  - Test single class: `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn -pl . test -Dtest=ClassName`
  - Compile only: `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn -DskipTests compile`
  - Full test: `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test`
- Test naming follows the existing pattern `<ClassName>Test` in `src/test/java/<same-package>/...`. Use `testutil.Mocks` for `Character` mocks.
- New packages: `client.bot` and `server.bot`. Tests mirror these in `src/test/java/`.
- Commit after each task with the message shown. Use `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` per repo convention.

---

## Task 1: Spike — investigate construction surface

**Goal:** Resolve the four open items called out in the spec by reading existing code and recording exact APIs in a single notes doc the rest of the plan references. **No production code in this task — only a notes doc.**

**Files:**
- Create: `docs/superpowers/notes/2026-05-08-player-bot-investigation.md`

- [ ] **Step 1: Read these files end-to-end**
  - `src/main/java/client/Client.java` — every method that touches `ioChannel` or other Netty fields
  - `src/main/java/client/Character.java` — find the constructor(s), the static factory(ies), and what `MapleMap.addPlayer` expects to be set
  - `src/main/java/server/maps/MapleMap.java` — `addPlayer`, `removePlayer`, `broadcastMessage`, `pickItemDrop`, `damageMonster`
  - `src/main/java/net/server/world/World.java` and `net/server/world/Party.java`/`PartyOperation.java` — how a party invite is queued and how it's accepted; whether the recipient's pending invites are server-side queryable
  - `src/main/java/net/server/handlers` (recursively) — find the whisper handler; note where the "user is offline" reply is produced
  - `src/main/java/tools/PacketCreator.java` — find `closeRangeAttack`, `rangedAttack`, `movePlayer`, `spawnPlayer`, `removePlayerFromMap` signatures
  - `src/main/java/server/TimerManager.java` — find the repeating-task API (likely `register(Runnable, long)` or similar)
  - `src/main/java/client/command/CommandsExecutor.java` — `addCommand(...)` signature and registration site (around the line that registers `GotoCommand`)
  - `src/main/java/mcp/McpServer.java` and `src/main/java/mcp/admin/AuditLog.java` — how new tools are constructed and registered, how mutating tools record audit entries

- [ ] **Step 2: Write the notes doc**

Create `docs/superpowers/notes/2026-05-08-player-bot-investigation.md` with these sections, each populated with concrete code references (`Class.method:line`):

```markdown
# Player Bot — Construction Surface Notes

## A. Client subclassing

- `Client` constructor signature: `public Client(Type type, long sessionId, String remoteAddress, PacketProcessor packetProcessor, int world, int channel)` (Client.java:158).
- Methods that touch `ioChannel` (must be no-ops or guarded in BotClient):
  <list each method name and line, e.g. "sendPacket — Client.java:1466">
- Methods/fields whose visibility must be relaxed from `private` to `protected` so `BotClient` can override or read them:
  <list each one with line numbers; if none, write "none">
- Constructor recommended for BotClient: `BotClient(int world, int channel)` — call `super(Type.CHANNEL, /*sessionId=*/-1, /*remoteAddress=*/"bot", /*packetProcessor=*/null, world, channel)`. If `super(...)` requires a non-null `PacketProcessor` for code paths the bot reaches, document the alternative here.

## B. Character construction without DB

- `Character` is constructed via: <document the exact call site, e.g. `Character.getDefault(client, jobId)` at Character.java:NNNN, OR a private constructor at Character.java:NNNN>.
- The minimum field set needed for `MapleMap.addPlayer(chr)` to succeed without NPE: <list>.
- The chosen path for the bot: <pick ONE of: (a) reuse an existing factory, (b) introduce a new package-private factory `Character.createBot(Client, Preset)` co-located with the existing factory>.
- Synthetic ID range collision: at boot, query `SELECT MIN(id) FROM characters` — if it returns a value `<= -1_000_000`, abort startup. Document the existing DB connection helper used by similar startup checks.

## C. Party invite

- Party invite handler class/method: <e.g. `PartyHandler.handlePacket` at PartyHandler.java:NN>.
- Server-side state for pending invites: <e.g. `Party` keeps no pending list; the invite is purely a packet to the recipient — OR `World` keeps a `Map<Integer,Integer> pendingInvites`>.
- Decision: choose the interception strategy and document. Recommended: add a `PartyInviteListener` interface invoked from the existing send-invite path; `BotClient` can register its bot to auto-accept. Alternative: poll the recipient's pending state if it exists.

## D. Whisper

- Whisper handler class/method: <e.g. `WhisperHandler.handlePacket` at WhisperHandler.java:NN>.
- "User is offline" branch: <line ref>.
- Decision for filtering whispers to bots: <e.g. add an early `if (BotManager.isBot(targetName)) return;` at WhisperHandler.java:NN>.

## E. Attack dispatch

- Equipped weapon type lookup: `Character.getJob().isA(...)` / `WeaponType.from(...)` / inventory `Equipped` slot — document the exact one-liner.
- Existing damage-application call from server-side combat: `MapleMap.damageMonster(Character attacker, Monster mob, int damage)` at MapleMap.java:NN.
- `PacketCreator.closeRangeAttack(...)` and `PacketCreator.rangedAttack(...)` signatures and what fields the bot needs to populate.

## F. TimerManager

- Repeating-task API: <e.g. `TimerManager.getInstance().register(Runnable, long delayMs)`> — document exact name and that it returns a `ScheduledFuture` so `BotScheduler.stop()` can cancel.

## G. CommandsExecutor registration

- Insertion point for `addCommand("bot", 1, BotCommand.class)` (around CommandsExecutor.java:402, after the existing `goto` line).

## H. MCP tool registration

- Site where existing tools (e.g. `MobWhereTool`) are constructed and added to the registry. Document constructor dependencies for each new tool (likely `BotManager`, `AuditLog`).
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/notes/2026-05-08-player-bot-investigation.md
git commit -m "$(cat <<'EOF'
Add player-bot construction-surface investigation notes

Spike output for the player-bot v1 plan. Documents exact APIs and
chosen interception points used by subsequent implementation tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**This task gates everything below.** Subsequent tasks reference sections of this notes doc by letter (A–H).

---

## Task 2: BotConfig — read `bots.*` from `config.yaml`

**Files:**
- Modify: `src/main/java/config/YamlConfig.java` — add a nested `BotConfig` field
- Create: `src/main/java/config/BotConfig.java`
- Create: `src/test/java/config/BotConfigTest.java`
- Modify: `config.yaml` — append `bots:` block (disabled by default)

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/config/BotConfigTest.java
package config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotConfigTest {

    @Test
    void defaultsAreDisabledAndConservative() {
        BotConfig c = new BotConfig();
        assertFalse(c.enabled);
        assertEquals(200, c.tick_ms);
        assertEquals(50, c.max_per_world);
        assertEquals(2000000, c.hp_pot_item_id);
        assertEquals(2000003, c.mp_pot_item_id);
        assertEquals(50, c.hp_pct_threshold);
        assertEquals(30, c.mp_pct_threshold);
        assertEquals(100, c.follow_radius);
        assertEquals(800, c.grind_radius);
        assertEquals(3000, c.revive_delay_ms);
        assertEquals("Bot", c.name_prefix);
        assertTrue(c.auto_accept_party);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn -DskipTests=false test -Dtest=BotConfigTest
```
Expected: compilation failure ("BotConfig not found").

- [ ] **Step 3: Implement `BotConfig`**

```java
// src/main/java/config/BotConfig.java
package config;

public class BotConfig {
    public boolean enabled = false;
    public int tick_ms = 200;
    public int max_per_world = 50;
    public int hp_pot_item_id = 2000000;
    public int mp_pot_item_id = 2000003;
    public int hp_pct_threshold = 50;
    public int mp_pct_threshold = 30;
    public int follow_radius = 100;
    public int grind_radius = 800;
    public int revive_delay_ms = 3000;
    public String name_prefix = "Bot";
    public boolean auto_accept_party = true;
}
```

- [ ] **Step 4: Wire into `YamlConfig`**

In `src/main/java/config/YamlConfig.java`, add (mirroring how other nested config blocks are declared — e.g. the `mcp` block from Slice 3 if present, otherwise the `irc` block):

```java
public BotConfig bots = new BotConfig();
```

If neither `mcp` nor `irc` follows the public-field pattern, follow whichever pattern `YamlConfig` already uses for top-level groups.

- [ ] **Step 5: Append to `config.yaml`**

Append to the end of the existing `config.yaml`:

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

- [ ] **Step 6: Run test to verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotConfigTest
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/config/BotConfig.java src/main/java/config/YamlConfig.java src/test/java/config/BotConfigTest.java config.yaml
git commit -m "Add BotConfig (disabled by default)"
```

---

## Task 3: BotIdAllocator — synthetic ID generator

Generates monotonically decreasing negative IDs starting at `-1_000_000`. Single source of synthetic IDs to avoid collision with real `characters.id`.

**Files:**
- Create: `src/main/java/server/bot/BotIdAllocator.java`
- Create: `src/test/java/server/bot/BotIdAllocatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/server/bot/BotIdAllocatorTest.java
package server.bot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotIdAllocatorTest {

    @Test
    void firstIdIsNegativeOneMillion() {
        BotIdAllocator a = new BotIdAllocator();
        assertEquals(-1_000_000, a.next());
    }

    @Test
    void idsAreMonotonicallyDecreasing() {
        BotIdAllocator a = new BotIdAllocator();
        int first = a.next();
        int second = a.next();
        int third = a.next();
        assertTrue(second < first);
        assertTrue(third < second);
        assertEquals(first - 1, second);
        assertEquals(second - 1, third);
    }

    @Test
    void allocatorStartsFromGivenSeed() {
        BotIdAllocator a = new BotIdAllocator(-2_000_000);
        assertEquals(-2_000_000, a.next());
        assertEquals(-2_000_001, a.next());
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotIdAllocatorTest
```
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
// src/main/java/server/bot/BotIdAllocator.java
package server.bot;

import java.util.concurrent.atomic.AtomicInteger;

public class BotIdAllocator {

    public static final int START = -1_000_000;

    private final AtomicInteger next;

    public BotIdAllocator() {
        this(START);
    }

    public BotIdAllocator(int seed) {
        this.next = new AtomicInteger(seed + 1);
    }

    public int next() {
        return next.decrementAndGet();
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotIdAllocatorTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/BotIdAllocator.java src/test/java/server/bot/BotIdAllocatorTest.java
git commit -m "Add BotIdAllocator (synthetic negative IDs)"
```

---

## Task 4: BotManager — registry of live bots

Holds the registry, enforces `max_per_world`, exposes lookup and despawn. Pure data-structure task — no Character interactions yet.

**Files:**
- Create: `src/main/java/server/bot/Bot.java` (skeleton — will grow in later tasks)
- Create: `src/main/java/server/bot/BotManager.java`
- Create: `src/test/java/server/bot/BotManagerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/server/bot/BotManagerTest.java
package server.bot;

import client.Character;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BotManagerTest {

    private static Bot fakeBot(int id, String name, int world) {
        Character chr = Mocks.chr(name);
        when(chr.getId()).thenReturn(id);
        when(chr.getWorld()).thenReturn(world);
        return new Bot(chr);
    }

    @Test
    void registerAndLookupById() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        assertSame(b, m.findById(-1_000_000));
    }

    @Test
    void registerAndLookupByName() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        assertSame(b, m.findByName("Bot01"));
        assertNull(m.findByName("notabot"));
    }

    @Test
    void unregisterRemoves() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "Bot01", 0);
        m.register(b);
        m.unregister(b);
        assertNull(m.findById(-1_000_000));
    }

    @Test
    void listInWorldReturnsOnlyMatching() {
        BotManager m = new BotManager(new BotConfig());
        m.register(fakeBot(-1_000_000, "A", 0));
        m.register(fakeBot(-1_000_001, "B", 0));
        m.register(fakeBot(-1_000_002, "C", 1));
        assertEquals(2, m.listInWorld(0).size());
        assertEquals(1, m.listInWorld(1).size());
    }

    @Test
    void enforcesMaxPerWorld() {
        BotConfig cfg = new BotConfig();
        cfg.max_per_world = 2;
        BotManager m = new BotManager(cfg);
        m.register(fakeBot(-1_000_000, "A", 0));
        m.register(fakeBot(-1_000_001, "B", 0));
        assertThrows(BotManager.AtCapException.class,
                () -> m.register(fakeBot(-1_000_002, "C", 0)));
    }

    @Test
    void activeBotsReturnsSnapshotIndependentOfMutation() {
        BotManager m = new BotManager(new BotConfig());
        Bot b = fakeBot(-1_000_000, "A", 0);
        m.register(b);
        var snapshot = m.activeBots();
        m.unregister(b);
        assertEquals(1, snapshot.size(), "snapshot must be independent of registry");
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotManagerTest
```
Expected: compilation failure.

- [ ] **Step 3: Implement `Bot` skeleton**

```java
// src/main/java/server/bot/Bot.java
package server.bot;

import client.Character;

public class Bot {

    public enum Mode { IDLE, FOLLOW, GRIND }

    private final Character character;
    private volatile Mode mode = Mode.IDLE;
    private volatile Integer targetCharId;
    private volatile String mobFilter;

    public Bot(Character character) {
        this.character = character;
    }

    public Character character() { return character; }
    public int id() { return character.getId(); }
    public String name() { return character.getName(); }
    public int world() { return character.getWorld(); }

    public Mode mode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public Integer targetCharId() { return targetCharId; }
    public void setTargetCharId(Integer id) { this.targetCharId = id; }

    public String mobFilter() { return mobFilter; }
    public void setMobFilter(String filter) { this.mobFilter = filter; }
}
```

- [ ] **Step 4: Implement `BotManager`**

```java
// src/main/java/server/bot/BotManager.java
package server.bot;

import config.BotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BotManager {

    public static class AtCapException extends RuntimeException {
        public AtCapException(int world, int cap) {
            super("bots.max_per_world (" + cap + ") reached for world " + world);
        }
    }

    private final BotConfig cfg;
    private final Map<Integer, Bot> byId = new ConcurrentHashMap<>();

    public BotManager(BotConfig cfg) {
        this.cfg = cfg;
    }

    public synchronized void register(Bot bot) {
        long inWorld = byId.values().stream().filter(b -> b.world() == bot.world()).count();
        if (inWorld >= cfg.max_per_world) {
            throw new AtCapException(bot.world(), cfg.max_per_world);
        }
        byId.put(bot.id(), bot);
    }

    public void unregister(Bot bot) {
        byId.remove(bot.id());
    }

    public Bot findById(int id) {
        return byId.get(id);
    }

    public Bot findByName(String name) {
        for (Bot b : byId.values()) {
            if (b.name().equals(name)) return b;
        }
        return null;
    }

    public List<Bot> listInWorld(int world) {
        List<Bot> out = new ArrayList<>();
        for (Bot b : byId.values()) {
            if (b.world() == world) out.add(b);
        }
        return out;
    }

    public List<Bot> activeBots() {
        return new ArrayList<>(byId.values());
    }

    public boolean isBotName(String name) {
        return findByName(name) != null;
    }
}
```

- [ ] **Step 5: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotManagerTest
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/server/bot/Bot.java src/main/java/server/bot/BotManager.java src/test/java/server/bot/BotManagerTest.java
git commit -m "Add BotManager and Bot skeleton"
```

---

## Task 5: BotClient — stub Client subclass

`BotClient` extends `Client`, no-ops `sendPacket`, routes `disconnect` to `BotManager`. Uses the visibility relaxations and overrides documented in section A of the investigation notes.

**Files:**
- Modify: `src/main/java/client/Client.java` (relax visibility per investigation notes section A)
- Create: `src/main/java/client/bot/BotClient.java`
- Create: `src/test/java/client/bot/BotClientTest.java`

- [ ] **Step 1: Apply Client visibility changes per notes section A**

For each method/field listed in notes section A, change `private` → `protected`. Make no other changes to `Client`. If notes say "none", skip this step.

- [ ] **Step 2: Write the failing test**

```java
// src/test/java/client/bot/BotClientTest.java
package client.bot;

import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.Bot;
import server.bot.BotManager;
import config.BotConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotClientTest {

    @Test
    void sendPacketIsNoOp() {
        BotClient c = new BotClient(0, 0);
        // Should not throw despite no Netty channel.
        c.sendPacket(Mockito.mock(Packet.class));
    }

    @Test
    void getRemoteAddressIsBotSentinel() {
        BotClient c = new BotClient(0, 0);
        assertEquals("bot", c.getRemoteAddress());
    }

    @Test
    void disconnectRoutesToBotManager() {
        BotManager mgr = new BotManager(new BotConfig());
        Bot bot = mock(Bot.class);
        when(bot.id()).thenReturn(-1_000_000);
        BotClient c = new BotClient(0, 0);
        c.attachBot(bot, mgr);
        // simulate the dispose hook
        c.disconnect(false, false);
        // Bot should have been despawned (manager.findById null)
        assertNull(mgr.findById(-1_000_000));
    }
}
```

- [ ] **Step 3: Implement `BotClient`**

```java
// src/main/java/client/bot/BotClient.java
package client.bot;

import client.Client;
import net.packet.Packet;
import server.bot.Bot;
import server.bot.BotManager;

public class BotClient extends Client {

    private Bot bot;
    private BotManager manager;

    public BotClient(int world, int channel) {
        super(Type.CHANNEL, /*sessionId=*/-1, /*remoteAddress=*/"bot",
              /*packetProcessor=*/null, world, channel);
    }

    public void attachBot(Bot bot, BotManager manager) {
        this.bot = bot;
        this.manager = manager;
    }

    @Override
    public void sendPacket(Packet packet) {
        // Bots do not render their own view; drop all outbound packets.
    }

    @Override
    public String getRemoteAddress() {
        return "bot";
    }

    @Override
    public void disconnect(boolean shutdown, boolean cashShop) {
        if (manager != null && bot != null) {
            manager.unregister(bot);
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotClientTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/client/Client.java src/main/java/client/bot/BotClient.java src/test/java/client/bot/BotClientTest.java
git commit -m "Add BotClient (no-op packet stub)"
```

---

## Task 6: BotFactory — spawn / despawn

Builds a `Character` for the bot using the path documented in notes section B, registers with `BotManager`, places into the target `MapleMap`. Uses notes section A for `BotClient` setup.

**Files:**
- Create: `src/main/java/client/bot/BotPreset.java`
- Create: `src/main/java/client/bot/BotFactory.java`
- Create: `src/test/java/client/bot/BotFactoryTest.java`

- [ ] **Step 1: Implement `BotPreset`**

```java
// src/main/java/client/bot/BotPreset.java
package client.bot;

public record BotPreset(String name, int jobId, int level, int hp, int mp) {
    public static final BotPreset BEGINNER_LV30 =
            new BotPreset("Beginner Lv 30", 0, 30, 1500, 200);
}
```

- [ ] **Step 2: Write the failing test**

```java
// src/test/java/client/bot/BotFactoryTest.java
package client.bot;

import config.BotConfig;
import org.junit.jupiter.api.Test;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;
import static org.junit.jupiter.api.Assertions.*;

class BotFactoryTest {

    @Test
    void spawnReturnsBotWithSyntheticNegativeId() {
        BotManager mgr = new BotManager(new BotConfig());
        BotIdAllocator ids = new BotIdAllocator();
        BotFactory factory = new BotFactory(new BotConfig(), mgr, ids,
                BotFactoryTest::fakePlacer);
        Bot bot = factory.spawn(0, 0, /*mapId=*/100000000, 0, 0, BotPreset.BEGINNER_LV30);
        assertTrue(bot.id() <= -1_000_000);
        assertSame(bot, mgr.findById(bot.id()));
    }

    @Test
    void spawnDisabledByConfigThrows() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = false;
        BotFactory factory = new BotFactory(cfg, new BotManager(cfg),
                new BotIdAllocator(), BotFactoryTest::fakePlacer);
        assertThrows(BotFactory.DisabledException.class,
                () -> factory.spawn(0, 0, 100000000, 0, 0, BotPreset.BEGINNER_LV30));
    }

    @Test
    void spawnNamesAreUniqueWithinSameRun() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(),
                BotFactoryTest::fakePlacer);
        Bot a = factory.spawn(0, 0, 100000000, 0, 0, BotPreset.BEGINNER_LV30);
        Bot b = factory.spawn(0, 0, 100000000, 0, 0, BotPreset.BEGINNER_LV30);
        assertNotEquals(a.name(), b.name());
        assertTrue(a.name().startsWith("Bot"));
    }

    @Test
    void despawnUnregistersAndCallsRemover() {
        BotConfig cfg = new BotConfig();
        cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        int[] removerCalled = {0};
        BotFactory.Remover remover = (chr, mapId) -> removerCalled[0]++;
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(),
                BotFactoryTest::fakePlacer, remover);
        Bot bot = factory.spawn(0, 0, 100000000, 0, 0, BotPreset.BEGINNER_LV30);
        factory.despawn(bot);
        assertEquals(1, removerCalled[0]);
        assertNull(mgr.findById(bot.id()));
    }

    private static void fakePlacer(client.Character chr, int mapId, int x, int y) {
        // no-op: tests don't load a real MapleMap
    }
}
```

- [ ] **Step 3: Implement `BotFactory`**

```java
// src/main/java/client/bot/BotFactory.java
package client.bot;

import client.Character;
import config.BotConfig;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;

import java.util.concurrent.atomic.AtomicInteger;

public class BotFactory {

    public static class DisabledException extends RuntimeException {
        public DisabledException() { super("bots.enabled is false"); }
    }

    public interface Placer {
        void placeOnMap(Character chr, int mapId, int x, int y);
    }

    public interface Remover {
        void removeFromMap(Character chr, int mapId);
    }

    private final BotConfig cfg;
    private final BotManager manager;
    private final BotIdAllocator ids;
    private final Placer placer;
    private final Remover remover;
    private final AtomicInteger nameSequence = new AtomicInteger(0);

    public BotFactory(BotConfig cfg, BotManager manager, BotIdAllocator ids, Placer placer) {
        this(cfg, manager, ids, placer, (chr, mapId) -> {});
    }

    public BotFactory(BotConfig cfg, BotManager manager, BotIdAllocator ids,
                      Placer placer, Remover remover) {
        this.cfg = cfg;
        this.manager = manager;
        this.ids = ids;
        this.placer = placer;
        this.remover = remover;
    }

    public Bot spawn(int world, int channel, int mapId, int x, int y, BotPreset preset) {
        if (!cfg.enabled) throw new DisabledException();

        int id = ids.next();
        String name = String.format("%s%02d", cfg.name_prefix, nameSequence.incrementAndGet());

        // The exact construction call here is documented in
        // docs/superpowers/notes/2026-05-08-player-bot-investigation.md section B.
        // The implementer must replace this line with the documented call:
        Character chr = BotCharacterFactory.create(world, channel, id, name, preset);

        Bot bot = new Bot(chr);
        manager.register(bot);
        placer.placeOnMap(chr, mapId, x, y);
        return bot;
    }

    public void despawn(Bot bot) {
        Character chr = bot.character();
        remover.removeFromMap(chr, chr.getMapId());
        manager.unregister(bot);
    }
}
```

- [ ] **Step 4: Implement `BotCharacterFactory` per notes section B**

Create `src/main/java/client/bot/BotCharacterFactory.java`. The exact body is determined by section B of the investigation notes. Two valid shapes:

**If section B chose "reuse existing factory":**

```java
package client.bot;

import client.Character;

class BotCharacterFactory {
    static Character create(int world, int channel, int id, String name, BotPreset preset) {
        // Replace this body with the call documented in notes section B, e.g.:
        //   BotClient client = new BotClient(world, channel);
        //   Character chr = Character.<existingFactoryName>(client, preset.jobId());
        //   chr.<setId|setIdInternal>(id);
        //   chr.setName(name);
        //   chr.setLevel(preset.level());
        //   chr.setHp(preset.hp()); chr.setMaxHp(preset.hp());
        //   chr.setMp(preset.mp()); chr.setMaxMp(preset.mp());
        //   return chr;
        throw new UnsupportedOperationException(
                "Implementer: replace with the call documented in investigation notes section B");
    }
}
```

**If section B chose "introduce a new package-private factory":**

Add the new factory to `client.Character` itself (or a new `client.CharacterFactory` class — whichever section B chose), and call it from here.

- [ ] **Step 5: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotFactoryTest
```
Expected: PASS.

If `BotCharacterFactory.create` is unreachable from the tests (because tests use `enabled=false` or the placer is a no-op), the test exercises the orchestration without invoking the factory body, so the `UnsupportedOperationException` placeholder is acceptable for the unit test only. It MUST be replaced before Task 19 (server boot wiring), or the server will throw at runtime.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/client/bot/BotPreset.java src/main/java/client/bot/BotFactory.java src/main/java/client/bot/BotCharacterFactory.java src/test/java/client/bot/BotFactoryTest.java
git commit -m "Add BotFactory and BotPreset"
```

---

## Task 7: BotScheduler — tick loop

One repeating `TimerManager` task (API per notes section F) iterates a snapshot of `BotManager.activeBots()` and calls `brain.tick(...)` per bot, with per-tick try/catch and a 3-strikes auto-despawn rule.

**Files:**
- Create: `src/main/java/server/bot/BotBrain.java` (interface only)
- Create: `src/main/java/server/bot/BotScheduler.java`
- Create: `src/test/java/server/bot/BotSchedulerTest.java`

- [ ] **Step 1: Implement the brain interface**

```java
// src/main/java/server/bot/BotBrain.java
package server.bot;

public interface BotBrain {
    void tick(Bot bot, long now);
}
```

- [ ] **Step 2: Write the failing test**

```java
// src/test/java/server/bot/BotSchedulerTest.java
package server.bot;

import client.Character;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BotSchedulerTest {

    private static Bot fakeBot(int id) {
        Character chr = Mocks.chr("Bot" + id);
        when(chr.getId()).thenReturn(id);
        when(chr.getWorld()).thenReturn(0);
        return new Bot(chr);
    }

    @Test
    void runOnceTicksAllBots() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        Bot b = fakeBot(-1_000_001);
        m.register(a); m.register(b);
        int[] ticks = {0};
        BotBrain brain = (bot, now) -> ticks[0]++;
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(123L);
        assertEquals(2, ticks[0]);
    }

    @Test
    void brainExceptionDoesNotKillLoop() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        Bot b = fakeBot(-1_000_001);
        m.register(a); m.register(b);
        int[] ticks = {0};
        BotBrain brain = (bot, now) -> {
            ticks[0]++;
            if (bot.id() == -1_000_000) throw new RuntimeException("boom");
        };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L);
        s.runOnce(2L);
        assertEquals(4, ticks[0], "both bots ticked twice despite one bot's exception");
    }

    @Test
    void threeConsecutiveFailuresDespawnsBot() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        m.register(a);
        BotBrain brain = (bot, now) -> { throw new RuntimeException("boom"); };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L);
        s.runOnce(2L);
        assertNotNull(m.findById(-1_000_000), "still alive after 2 failures");
        s.runOnce(3L);
        assertNull(m.findById(-1_000_000), "auto-despawned after 3 failures");
    }

    @Test
    void successResetsFailureCounter() {
        BotManager m = new BotManager(new BotConfig());
        Bot a = fakeBot(-1_000_000);
        m.register(a);
        int[] failNext = {2}; // fail twice, then succeed, then fail twice more
        BotBrain brain = (bot, now) -> {
            if (failNext[0] > 0) { failNext[0]--; throw new RuntimeException("boom"); }
        };
        BotScheduler s = new BotScheduler(m, brain, new BotConfig(),
                (bot) -> m.unregister(bot));
        s.runOnce(1L); s.runOnce(2L); // 2 failures
        s.runOnce(3L);                 // success — counter reset
        failNext[0] = 2;
        s.runOnce(4L); s.runOnce(5L); // 2 more failures
        assertNotNull(m.findById(-1_000_000), "should still be alive (no 3-in-a-row run)");
    }
}
```

- [ ] **Step 3: Implement `BotScheduler`**

```java
// src/main/java/server/bot/BotScheduler.java
package server.bot;

import config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);
    private static final int CONSECUTIVE_FAILURE_LIMIT = 3;

    public interface Despawner { void despawn(Bot bot); }

    private final BotManager manager;
    private final BotBrain brain;
    private final BotConfig cfg;
    private final Despawner despawner;
    private final Map<Integer, Integer> consecutiveFailures = new HashMap<>();
    private ScheduledFuture<?> handle;

    public BotScheduler(BotManager manager, BotBrain brain, BotConfig cfg, Despawner despawner) {
        this.manager = manager;
        this.brain = brain;
        this.cfg = cfg;
        this.despawner = despawner;
    }

    public void start() {
        // Replace `register(...)` with the exact API documented in notes section F.
        handle = TimerManager.getInstance().register(
                () -> runOnce(System.currentTimeMillis()),
                cfg.tick_ms);
    }

    public void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }

    /** Visible for tests. */
    public void runOnce(long now) {
        for (Bot bot : manager.activeBots()) {
            try {
                brain.tick(bot, now);
                consecutiveFailures.remove(bot.id());
            } catch (Throwable t) {
                int n = consecutiveFailures.getOrDefault(bot.id(), 0) + 1;
                consecutiveFailures.put(bot.id(), n);
                log.warn("bot tick failed (id={}, count={})", bot.id(), n, t);
                if (n >= CONSECUTIVE_FAILURE_LIMIT) {
                    log.warn("auto-despawning bot {} after {} consecutive failures",
                            bot.id(), n);
                    consecutiveFailures.remove(bot.id());
                    try { despawner.despawn(bot); } catch (Throwable t2) {
                        log.warn("despawn failed", t2);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotSchedulerTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/BotBrain.java src/main/java/server/bot/BotScheduler.java src/test/java/server/bot/BotSchedulerTest.java
git commit -m "Add BotScheduler with auto-despawn on repeated failures"
```

---

## Task 8: BotBrain — IDLE skeleton + decision dispatcher

Introduce `BotAction` (the per-tick decision) and `DefaultBotBrain` with the priority-ordered dispatcher. Only the IDLE branch is implemented — the rest return `BotAction.IDLE` for now and will be filled in by Tasks 9–14.

**Files:**
- Create: `src/main/java/server/bot/BotAction.java`
- Create: `src/main/java/server/bot/DefaultBotBrain.java`
- Create: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Implement `BotAction`**

```java
// src/main/java/server/bot/BotAction.java
package server.bot;

public enum BotAction {
    IDLE,
    USE_HP_POT,
    USE_MP_POT,
    RETREAT,
    WAIT_REVIVE,
    ACCEPT_PARTY_INVITE,
    WALK_TO_PORTAL,
    STEP_TOWARD_TARGET,
    STEP_TOWARD_MOB,
    ATTACK_MELEE,
    ATTACK_RANGED,
    PICKUP
}
```

- [ ] **Step 2: Write the failing test (IDLE only)**

```java
// src/test/java/server/bot/DefaultBotBrainTest.java
package server.bot;

import client.Character;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class DefaultBotBrainTest {

    static Bot aliveBot() {
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(-1_000_000);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getHp()).thenReturn(1000);
        when(chr.getMaxHp()).thenReturn(1000);
        when(chr.getMp()).thenReturn(200);
        when(chr.getMaxMp()).thenReturn(200);
        return new Bot(chr);
    }

    @Test
    void modeIdleReturnsIdle() {
        DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
        assertEquals(BotAction.IDLE, b.decide(aliveBot(), 0L));
    }
}
```

- [ ] **Step 3: Define a small `WorldView` indirection so the brain is testable without a full server**

```java
// src/main/java/server/bot/WorldView.java
package server.bot;

import client.Character;

import java.util.List;

/**
 * Read-only view the brain uses to look at server state.
 * Production impl wraps `Server.getInstance()` and `MapleMap`.
 * Tests provide a fake.
 */
public interface WorldView {
    Character findCharacterById(int id);
    List<Integer> nearbyMobIds(Bot bot, int radius);
    boolean hasItemDropInPickupRadius(Bot bot);
    boolean hasInventorySpaceForNearbyDrops(Bot bot);
    boolean hasPendingPartyInvite(Bot bot);
    int findNearestPortalToMap(Bot bot, int targetMapId); // -1 if none
}
```

- [ ] **Step 4: Add a `FakeWorldView` for tests**

```java
// src/test/java/server/bot/FakeWorldView.java
package server.bot;

import client.Character;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FakeWorldView implements WorldView {
    final Map<Integer, Character> chars = new HashMap<>();
    List<Integer> nearbyMobs = List.of();
    boolean hasItem = false;
    boolean hasInvSpace = true;
    boolean hasInvite = false;
    int nearestPortalToTarget = -1;

    @Override public Character findCharacterById(int id) { return chars.get(id); }
    @Override public List<Integer> nearbyMobIds(Bot bot, int radius) { return nearbyMobs; }
    @Override public boolean hasItemDropInPickupRadius(Bot bot) { return hasItem; }
    @Override public boolean hasInventorySpaceForNearbyDrops(Bot bot) { return hasInvSpace; }
    @Override public boolean hasPendingPartyInvite(Bot bot) { return hasInvite; }
    @Override public int findNearestPortalToMap(Bot bot, int t) { return nearestPortalToTarget; }
}
```

- [ ] **Step 5: Implement `DefaultBotBrain` with the IDLE branch only**

```java
// src/main/java/server/bot/DefaultBotBrain.java
package server.bot;

import config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultBotBrain implements BotBrain {

    private static final Logger log = LoggerFactory.getLogger(DefaultBotBrain.class);

    private final BotConfig cfg;
    private final WorldView world;

    public DefaultBotBrain(BotConfig cfg, WorldView world) {
        this.cfg = cfg;
        this.world = world;
    }

    @Override
    public void tick(Bot bot, long now) {
        BotAction action = decide(bot, now);
        execute(bot, action, now);
    }

    /** Visible for tests. */
    public BotAction decide(Bot bot, long now) {
        // Tasks 9-14 will fill in the priority-ordered branches.
        return BotAction.IDLE;
    }

    void execute(Bot bot, BotAction action, long now) {
        if (action == BotAction.IDLE) return;
        // Tasks 9-14 add execution branches.
    }
}
```

- [ ] **Step 6: Run test, verify it passes**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=DefaultBotBrainTest
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/server/bot/BotAction.java src/main/java/server/bot/WorldView.java src/main/java/server/bot/DefaultBotBrain.java src/test/java/server/bot/DefaultBotBrainTest.java src/test/java/server/bot/FakeWorldView.java
git commit -m "Add BotBrain skeleton (IDLE)"
```

---

## Task 9: Brain — survival rule (HP/MP pots, retreat)

Adds the highest-priority branch. Threshold checks against `BotConfig`. Uses `Character` inventory to check for pots.

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java` — `decide` method, add survival branch
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java` — add 4 tests

- [ ] **Step 1: Add tests**

Append to `DefaultBotBrainTest`:

```java
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;

@Test
void lowHpWithPotUsesPot() {
    Bot bot = aliveBot();
    Character chr = bot.character();
    when(chr.getHp()).thenReturn(100); // 10% of 1000
    Inventory use = org.mockito.Mockito.mock(Inventory.class);
    when(chr.getInventory(InventoryType.USE)).thenReturn(use);
    Item pot = org.mockito.Mockito.mock(Item.class);
    when(pot.getItemId()).thenReturn(2000000);
    when(use.findById(2000000)).thenReturn(pot);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.USE_HP_POT, b.decide(bot, 0L));
}

@Test
void lowHpWithoutPotRetreats() {
    Bot bot = aliveBot();
    Character chr = bot.character();
    when(chr.getHp()).thenReturn(100);
    Inventory use = org.mockito.Mockito.mock(Inventory.class);
    when(chr.getInventory(InventoryType.USE)).thenReturn(use);
    when(use.findById(2000000)).thenReturn(null);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.RETREAT, b.decide(bot, 0L));
}

@Test
void lowMpWithPotUsesMpPot() {
    Bot bot = aliveBot();
    Character chr = bot.character();
    when(chr.getMp()).thenReturn(20); // 10% of 200
    Inventory use = org.mockito.Mockito.mock(Inventory.class);
    when(chr.getInventory(InventoryType.USE)).thenReturn(use);
    Item pot = org.mockito.Mockito.mock(Item.class);
    when(pot.getItemId()).thenReturn(2000003);
    when(use.findById(2000003)).thenReturn(pot);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.USE_MP_POT, b.decide(bot, 0L));
}

@Test
void hpAboveThresholdDoesNotUsePot() {
    Bot bot = aliveBot();
    Character chr = bot.character();
    when(chr.getHp()).thenReturn(900); // 90%
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
}
```

- [ ] **Step 2: Run tests, verify they fail**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=DefaultBotBrainTest
```
Expected: 4 new tests fail.

- [ ] **Step 3: Add survival branch to `decide`**

Replace the body of `decide(Bot, long)` in `DefaultBotBrain.java` with:

```java
public BotAction decide(Bot bot, long now) {
    // 1. Survival
    Character chr = bot.character();
    int hpPct = chr.getMaxHp() == 0 ? 100 : (chr.getHp() * 100 / chr.getMaxHp());
    if (hpPct < cfg.hp_pct_threshold) {
        if (hasItem(chr, cfg.hp_pot_item_id)) return BotAction.USE_HP_POT;
        return BotAction.RETREAT;
    }
    int mpPct = chr.getMaxMp() == 0 ? 100 : (chr.getMp() * 100 / chr.getMaxMp());
    if (mpPct < cfg.mp_pct_threshold) {
        if (hasItem(chr, cfg.mp_pot_item_id)) return BotAction.USE_MP_POT;
    }
    return BotAction.IDLE;
}

private static boolean hasItem(Character chr, int itemId) {
    var inv = chr.getInventory(client.inventory.InventoryType.USE);
    if (inv == null) return false;
    return inv.findById(itemId) != null;
}
```

- [ ] **Step 4: Run tests, verify they pass**

Same command as Step 2. Expected: PASS for all `DefaultBotBrainTest` tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/test/java/server/bot/DefaultBotBrainTest.java
git commit -m "Brain: survival branch (HP/MP pot, retreat)"
```

---

## Task 10: Brain — death and revive

Adds the dead-state branch. When bot is dead, returns `WAIT_REVIVE`. The execute branch schedules a `revive_delay_ms` timer that resets HP/MP and re-broadcasts spawn (using `Placer` from Task 6).

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java`
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Add test**

```java
@Test
void deadBotReturnsWaitRevive() {
    Bot bot = aliveBot();
    Character chr = bot.character();
    when(chr.isAlive()).thenReturn(false);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.WAIT_REVIVE, b.decide(bot, 0L));
}
```

- [ ] **Step 2: Run, expect failure**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=DefaultBotBrainTest
```

- [ ] **Step 3: Add dead-state branch ABOVE the survival branch in `decide`**

```java
public BotAction decide(Bot bot, long now) {
    Character chr = bot.character();
    // 1. Dead — wait for revive (no further checks once dead)
    if (!chr.isAlive()) return BotAction.WAIT_REVIVE;
    // 2. Survival (existing body unchanged)
    int hpPct = ...;
    ...
}
```

- [ ] **Step 4: Run, verify pass**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/test/java/server/bot/DefaultBotBrainTest.java
git commit -m "Brain: dead -> WAIT_REVIVE"
```

Note: the actual revive scheduling (resetting HP and re-spawning) requires the Placer from Task 6 and is handled in Task 19 (server boot wiring) where the brain receives the production `WorldView` and Placer. This task only covers the decision.

---

## Task 11: Brain — FOLLOW

Pull target via `WorldView.findCharacterById`. Same map → step toward target. Different map → walk to portal that leads to target's map.

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java`
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Add tests**

```java
private static Bot followingBot(int targetId) {
    Bot bot = aliveBot();
    bot.setMode(Bot.Mode.FOLLOW);
    bot.setTargetCharId(targetId);
    return bot;
}

@Test
void followTargetSameMapInRadiusIdles() {
    Bot bot = followingBot(123);
    Character target = Mocks.chr("Player");
    when(target.getMapId()).thenReturn(100000000);
    when(target.getPosition()).thenReturn(new java.awt.Point(0, 0));
    when(bot.character().getMapId()).thenReturn(100000000);
    when(bot.character().getPosition()).thenReturn(new java.awt.Point(50, 0));
    FakeWorldView w = new FakeWorldView();
    w.chars.put(123, target);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
}

@Test
void followTargetSameMapOutOfRadiusSteps() {
    Bot bot = followingBot(123);
    Character target = Mocks.chr("Player");
    when(target.getMapId()).thenReturn(100000000);
    when(target.getPosition()).thenReturn(new java.awt.Point(500, 0));
    when(bot.character().getMapId()).thenReturn(100000000);
    when(bot.character().getPosition()).thenReturn(new java.awt.Point(0, 0));
    FakeWorldView w = new FakeWorldView();
    w.chars.put(123, target);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.STEP_TOWARD_TARGET, b.decide(bot, 0L));
}

@Test
void followTargetOnDifferentMapWalksToPortal() {
    Bot bot = followingBot(123);
    Character target = Mocks.chr("Player");
    when(target.getMapId()).thenReturn(100000001);
    when(bot.character().getMapId()).thenReturn(100000000);
    FakeWorldView w = new FakeWorldView();
    w.chars.put(123, target);
    w.nearestPortalToTarget = 5; // a valid portal id
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.WALK_TO_PORTAL, b.decide(bot, 0L));
}

@Test
void followTargetGoneFallsBackToIdle() {
    Bot bot = followingBot(123);
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
    // also: the brain should clear the target. Verify behavior:
    assertNull(bot.targetCharId());
}
```

- [ ] **Step 2: Run, expect failures**

- [ ] **Step 3: Add FOLLOW branch AFTER survival, BEFORE the existing IDLE return**

```java
// 4. Follow
if (bot.mode() == Bot.Mode.FOLLOW && bot.targetCharId() != null) {
    Character target = world.findCharacterById(bot.targetCharId());
    if (target == null) {
        bot.setTargetCharId(null);
        return BotAction.IDLE;
    }
    if (target.getMapId() != chr.getMapId()) {
        int portalId = world.findNearestPortalToMap(bot, target.getMapId());
        return portalId >= 0 ? BotAction.WALK_TO_PORTAL : BotAction.IDLE;
    }
    int dx = target.getPosition().x - chr.getPosition().x;
    int dy = target.getPosition().y - chr.getPosition().y;
    int distSq = dx*dx + dy*dy;
    if (distSq <= cfg.follow_radius * cfg.follow_radius) return BotAction.IDLE;
    return BotAction.STEP_TOWARD_TARGET;
}
```

- [ ] **Step 4: Run, verify pass**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/test/java/server/bot/DefaultBotBrainTest.java
git commit -m "Brain: FOLLOW (same-map step, cross-map portal walk)"
```

---

## Task 12: Brain — GRIND with melee/ranged dispatch

Pick a nearby mob; if in range, attack with the appropriate packet based on equipped weapon.

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java`
- Modify: `src/main/java/server/bot/WorldView.java` — add `isRangedWeapon(Bot)`
- Modify: `src/test/java/server/bot/FakeWorldView.java`
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Extend `WorldView`**

```java
boolean isRangedWeapon(Bot bot);
boolean mobInAttackRange(Bot bot, int mobId);
```

And in `FakeWorldView`:

```java
boolean ranged = false;
boolean inRange = false;
@Override public boolean isRangedWeapon(Bot bot) { return ranged; }
@Override public boolean mobInAttackRange(Bot bot, int mobId) { return inRange; }
```

- [ ] **Step 2: Add tests**

```java
private static Bot grindingBot() {
    Bot bot = aliveBot();
    bot.setMode(Bot.Mode.GRIND);
    when(bot.character().getMapId()).thenReturn(100000000);
    when(bot.character().getPosition()).thenReturn(new java.awt.Point(0, 0));
    return bot;
}

@Test
void grindNoMobsIdles() {
    Bot bot = grindingBot();
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), new FakeWorldView());
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
}

@Test
void grindMobOutOfRangeSteps() {
    Bot bot = grindingBot();
    FakeWorldView w = new FakeWorldView();
    w.nearbyMobs = java.util.List.of(101);
    w.inRange = false;
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.STEP_TOWARD_MOB, b.decide(bot, 0L));
}

@Test
void grindMeleeAttacks() {
    Bot bot = grindingBot();
    FakeWorldView w = new FakeWorldView();
    w.nearbyMobs = java.util.List.of(101);
    w.inRange = true;
    w.ranged = false;
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.ATTACK_MELEE, b.decide(bot, 0L));
}

@Test
void grindRangedAttacks() {
    Bot bot = grindingBot();
    FakeWorldView w = new FakeWorldView();
    w.nearbyMobs = java.util.List.of(101);
    w.inRange = true;
    w.ranged = true;
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.ATTACK_RANGED, b.decide(bot, 0L));
}
```

- [ ] **Step 3: Add GRIND branch**

In `decide`, after FOLLOW:

```java
// 5. Grind
if (bot.mode() == Bot.Mode.GRIND) {
    java.util.List<Integer> mobs = world.nearbyMobIds(bot, cfg.grind_radius);
    if (mobs.isEmpty()) return BotAction.IDLE;
    int target = mobs.get(0); // nearest by convention
    if (!world.mobInAttackRange(bot, target)) return BotAction.STEP_TOWARD_MOB;
    return world.isRangedWeapon(bot) ? BotAction.ATTACK_RANGED : BotAction.ATTACK_MELEE;
}
```

- [ ] **Step 4: Run, verify pass**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/main/java/server/bot/WorldView.java src/test/java/server/bot/DefaultBotBrainTest.java src/test/java/server/bot/FakeWorldView.java
git commit -m "Brain: GRIND with melee/ranged dispatch"
```

---

## Task 13: Brain — LOOT

Loot has lower priority than GRIND so a grinder doesn't abandon mobs to grab pots, but higher priority than IDLE.

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java`
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Add tests**

```java
@Test
void lootInRangeWithSpacePicksUp() {
    Bot bot = aliveBot();
    FakeWorldView w = new FakeWorldView();
    w.hasItem = true;
    w.hasInvSpace = true;
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.PICKUP, b.decide(bot, 0L));
}

@Test
void lootInRangeButFullSkips() {
    Bot bot = aliveBot();
    FakeWorldView w = new FakeWorldView();
    w.hasItem = true;
    w.hasInvSpace = false;
    DefaultBotBrain b = new DefaultBotBrain(new BotConfig(), w);
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
}
```

- [ ] **Step 2: Add LOOT branch (last before IDLE return)**

```java
// 6. Loot
if (world.hasItemDropInPickupRadius(bot)
        && world.hasInventorySpaceForNearbyDrops(bot)) {
    return BotAction.PICKUP;
}
```

- [ ] **Step 3: Run, verify pass**

- [ ] **Step 4: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/test/java/server/bot/DefaultBotBrainTest.java
git commit -m "Brain: LOOT pickup"
```

---

## Task 14: Brain — party invite auto-accept + leadership reject

Insert the party-invite branch between survival and follow (per spec "rule 3"). Leadership reject is handled at the integration point documented in notes section C, not in the brain.

**Files:**
- Modify: `src/main/java/server/bot/DefaultBotBrain.java`
- Modify: `src/test/java/server/bot/DefaultBotBrainTest.java`

- [ ] **Step 1: Add tests**

```java
@Test
void pendingInviteAutoAccepts() {
    Bot bot = aliveBot();
    FakeWorldView w = new FakeWorldView();
    w.hasInvite = true;
    BotConfig cfg = new BotConfig();
    cfg.auto_accept_party = true;
    DefaultBotBrain b = new DefaultBotBrain(cfg, w);
    assertEquals(BotAction.ACCEPT_PARTY_INVITE, b.decide(bot, 0L));
}

@Test
void pendingInviteIgnoredWhenAutoAcceptOff() {
    Bot bot = aliveBot();
    FakeWorldView w = new FakeWorldView();
    w.hasInvite = true;
    BotConfig cfg = new BotConfig();
    cfg.auto_accept_party = false;
    DefaultBotBrain b = new DefaultBotBrain(cfg, w);
    assertEquals(BotAction.IDLE, b.decide(bot, 0L));
}
```

- [ ] **Step 2: Add branch between survival and follow**

```java
// 3. Pending party invite
if (cfg.auto_accept_party && world.hasPendingPartyInvite(bot)) {
    return BotAction.ACCEPT_PARTY_INVITE;
}
```

- [ ] **Step 3: Run, verify pass**

- [ ] **Step 4: Implement leadership-reject hook per notes section C**

The exact integration point depends on notes section C. Add a `BotPartyHook` class in `client.bot` that the existing party code consults; if it sees a leadership transfer targeted at a bot, it reroutes to "leave party" instead. Mirror the pattern that the existing whisper handler uses for filters. Add a unit test in `client/bot/BotPartyHookTest.java`. (If notes section C concluded that the existing party code already supports a "decline leadership" return path, register the bot for it instead and just write the unit test.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/DefaultBotBrain.java src/main/java/client/bot/BotPartyHook.java src/test/java/server/bot/DefaultBotBrainTest.java src/test/java/client/bot/BotPartyHookTest.java
git commit -m "Brain: party invite auto-accept + leadership reject"
```

---

## Task 15: BotCommand — `@bot` CLI

Adds `@bot spawn|follow|grind|stop|despawn|list` and registers it in `CommandsExecutor` at the site noted in section G.

**Files:**
- Create: `src/main/java/client/command/commands/gm1/BotCommand.java`
- Modify: `src/main/java/client/command/CommandsExecutor.java` — add registration line near the existing `goto` registration
- Create: `src/test/java/client/command/commands/gm1/BotCommandTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/client/command/commands/gm1/BotCommandTest.java
package client.command.commands.gm1;

import client.Character;
import client.Client;
import client.bot.BotFactory;
import client.bot.BotPreset;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import server.bot.Bot;
import server.bot.BotIdAllocator;
import server.bot.BotManager;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotCommandTest {

    @Test
    void spawnCallsFactoryAtPlayerPosition() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        BotIdAllocator ids = new BotIdAllocator();
        BotFactory.Placer placer = (chr,m,x,y) -> {};
        BotFactory factory = new BotFactory(cfg, mgr, ids, placer);
        Character chr = Mocks.chr("GM");
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.getPosition()).thenReturn(new java.awt.Point(10, 20));
        when(chr.getWorld()).thenReturn(0);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getWorld()).thenReturn(0);
        when(c.getChannel()).thenReturn(0);

        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"spawn"});
        assertEquals(1, mgr.activeBots().size());
    }

    @Test
    void despawnRemovesBotByName() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Bot bot = new Bot(Mocks.chr("Bot01"));
        when(bot.character().getId()).thenReturn(-1_000_000);
        when(bot.character().getWorld()).thenReturn(0);
        mgr.register(bot);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,c,d)->{});
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(Mocks.chr("GM"));
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"despawn", "Bot01"});
        assertNull(mgr.findByName("Bot01"));
    }

    @Test
    void followSetsModeAndTarget() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        Bot bot = new Bot(Mocks.chr("Bot01"));
        when(bot.character().getId()).thenReturn(-1_000_000);
        when(bot.character().getWorld()).thenReturn(0);
        mgr.register(bot);
        Character gm = Mocks.chr("GM");
        when(gm.getId()).thenReturn(99);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,d,e)->{});
        BotCommand cmd = new BotCommand(factory, mgr);
        cmd.execute(c, new String[]{"follow", "Bot01"});
        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(99, bot.targetCharId());
    }
}
```

- [ ] **Step 2: Implement `BotCommand`**

```java
// src/main/java/client/command/commands/gm1/BotCommand.java
package client.command.commands.gm1;

import client.Client;
import client.bot.BotFactory;
import client.bot.BotPreset;
import client.command.Command;
import server.bot.Bot;
import server.bot.BotManager;

public class BotCommand extends Command {

    {
        setDescription("Spawn / drive player-bots. Subcommands: spawn, follow <name>, grind [filter], stop, despawn [name], list.");
    }

    private final BotFactory factory;
    private final BotManager manager;

    // Reflective construction by CommandsExecutor requires a no-arg ctor;
    // wire dependencies in via setters from Server boot.
    public BotCommand() {
        this(null, null);
    }

    public BotCommand(BotFactory factory, BotManager manager) {
        this.factory = factory != null ? factory : Holder.factory;
        this.manager = manager != null ? manager : Holder.manager;
    }

    /** Wired once at server boot. */
    public static void wire(BotFactory f, BotManager m) {
        Holder.factory = f;
        Holder.manager = m;
    }
    private static class Holder {
        static BotFactory factory;
        static BotManager manager;
    }

    @Override
    public void execute(Client c, String[] params) {
        if (factory == null || manager == null) {
            c.getPlayer().dropMessage(1, "bots disabled (not wired)");
            return;
        }
        if (params.length == 0) {
            c.getPlayer().dropMessage(1, "usage: @bot spawn|follow <name>|grind [filter]|stop|despawn [name]|list");
            return;
        }
        switch (params[0]) {
            case "spawn"   -> spawn(c);
            case "follow"  -> follow(c, params);
            case "grind"   -> grind(c, params);
            case "stop"    -> stop(c, params);
            case "despawn" -> despawn(c, params);
            case "list"    -> list(c);
            default -> c.getPlayer().dropMessage(1, "unknown subcommand: " + params[0]);
        }
    }

    private void spawn(Client c) {
        var p = c.getPlayer();
        try {
            Bot bot = factory.spawn(p.getWorld(), c.getChannel(), p.getMapId(),
                    p.getPosition().x, p.getPosition().y, BotPreset.BEGINNER_LV30);
            p.dropMessage(5, "spawned " + bot.name() + " (id=" + bot.id() + ")");
        } catch (BotFactory.DisabledException e) {
            p.dropMessage(1, "bots disabled: set bots.enabled: true in config.yaml");
        } catch (BotManager.AtCapException e) {
            p.dropMessage(1, e.getMessage());
        }
    }

    private void follow(Client c, String[] params) {
        if (params.length < 2) { c.getPlayer().dropMessage(1, "usage: @bot follow <bot-name>"); return; }
        Bot bot = manager.findByName(params[1]);
        if (bot == null) { c.getPlayer().dropMessage(1, "no bot named " + params[1]); return; }
        bot.setMode(Bot.Mode.FOLLOW);
        bot.setTargetCharId(c.getPlayer().getId());
        c.getPlayer().dropMessage(5, bot.name() + " is now following you");
    }

    private void grind(Client c, String[] params) {
        var bots = manager.listInWorld(c.getPlayer().getWorld());
        if (bots.isEmpty()) { c.getPlayer().dropMessage(1, "no bots in your world"); return; }
        Bot bot = bots.get(0);
        bot.setMode(Bot.Mode.GRIND);
        if (params.length >= 2) bot.setMobFilter(params[1]);
        c.getPlayer().dropMessage(5, bot.name() + " is now grinding");
    }

    private void stop(Client c, String[] params) {
        if (params.length >= 2) {
            Bot b = manager.findByName(params[1]);
            if (b != null) b.setMode(Bot.Mode.IDLE);
        } else {
            for (Bot b : manager.listInWorld(c.getPlayer().getWorld())) b.setMode(Bot.Mode.IDLE);
        }
        c.getPlayer().dropMessage(5, "stopped");
    }

    private void despawn(Client c, String[] params) {
        if (params.length < 2) { c.getPlayer().dropMessage(1, "usage: @bot despawn <bot-name>"); return; }
        Bot bot = manager.findByName(params[1]);
        if (bot == null) { c.getPlayer().dropMessage(1, "no bot named " + params[1]); return; }
        factory.despawn(bot);
        c.getPlayer().dropMessage(5, "despawned " + params[1]);
    }

    private void list(Client c) {
        var bots = manager.listInWorld(c.getPlayer().getWorld());
        if (bots.isEmpty()) { c.getPlayer().dropMessage(5, "no bots in this world"); return; }
        StringBuilder s = new StringBuilder("bots in this world: ");
        for (Bot b : bots) s.append(b.name()).append("(").append(b.mode()).append(") ");
        c.getPlayer().dropMessage(5, s.toString());
    }
}
```

- [ ] **Step 3: Register the command**

In `CommandsExecutor.java`, add (next to the existing `goto` registration around line 402):

```java
addCommand("bot", 1, client.command.commands.gm1.BotCommand.class);
```

- [ ] **Step 4: Run tests, verify pass**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=BotCommandTest
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/client/command/commands/gm1/BotCommand.java src/main/java/client/command/CommandsExecutor.java src/test/java/client/command/commands/gm1/BotCommandTest.java
git commit -m "Add @bot command (spawn/follow/grind/stop/despawn/list)"
```

---

## Task 16: MCP `cosmic.bot.spawn`

Mutating tool, audited via `mcp_admin_audit`.

**Files:**
- Create: `src/main/java/mcp/tools/BotSpawnTool.java`
- Create: `src/test/java/mcp/tools/BotSpawnToolTest.java`

- [ ] **Step 1: Write failing test**

Mirror `MobWhereToolTest` style. Construct `BotSpawnTool(factory, manager, auditLog)`. Call with valid args, assert: bot registered with manager; audit log received one row; output contains the synthetic id.

```java
// src/test/java/mcp/tools/BotSpawnToolTest.java
package mcp.tools;

import client.bot.BotFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.BotConfig;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.BotIdAllocator;
import server.bot.BotManager;
import static org.junit.jupiter.api.Assertions.*;

class BotSpawnToolTest {

    @Test
    void spawnRegistersBotAndAudits() throws Tool.ToolException {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,c,d)->{});
        AuditLog audit = Mockito.mock(AuditLog.class);
        BotSpawnTool tool = new BotSpawnTool(factory, mgr, audit);

        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world", 0); args.put("channel", 0);
        args.put("map", 100000000); args.put("x", 0); args.put("y", 0);
        JsonNode out = tool.call(args);

        assertTrue(out.get("bot_id").asInt() <= -1_000_000);
        assertEquals(1, mgr.activeBots().size());
        Mockito.verify(audit).record(Mockito.eq("cosmic.bot.spawn"), Mockito.any(), Mockito.any());
    }

    @Test
    void disabledReturnsToolException() {
        BotConfig cfg = new BotConfig(); cfg.enabled = false;
        BotManager mgr = new BotManager(cfg);
        BotFactory factory = new BotFactory(cfg, mgr, new BotIdAllocator(), (a,b,c,d)->{});
        BotSpawnTool tool = new BotSpawnTool(factory, mgr, Mockito.mock(AuditLog.class));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("world",0).put("channel",0).put("map",100000000).put("x",0).put("y",0);
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }
}
```

(If `AuditLog` doesn't have a `record(String, JsonNode beforeJson, JsonNode afterJson)` method, replace the call with the actual method documented in notes section H, and adjust the test verifier.)

- [ ] **Step 2: Implement**

```java
// src/main/java/mcp/tools/BotSpawnTool.java
package mcp.tools;

import client.bot.BotFactory;
import client.bot.BotPreset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.bot.Bot;
import server.bot.BotManager;

public class BotSpawnTool implements Tool {

    private final BotFactory factory;
    private final BotManager manager;
    private final AuditLog audit;

    public BotSpawnTool(BotFactory factory, BotManager manager, AuditLog audit) {
        this.factory = factory;
        this.manager = manager;
        this.audit = audit;
    }

    @Override public String name() { return "cosmic.bot.spawn"; }
    @Override public String description() { return "Spawn an in-process player-bot at the given world/channel/map/position."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("world").put("type", "integer");
        props.putObject("channel").put("type", "integer");
        props.putObject("map").put("type", "integer");
        props.putObject("x").put("type", "integer");
        props.putObject("y").put("type", "integer");
        root.putArray("required").add("world").add("channel").add("map").add("x").add("y");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        try {
            int world = args.get("world").asInt();
            int channel = args.get("channel").asInt();
            int map = args.get("map").asInt();
            int x = args.get("x").asInt();
            int y = args.get("y").asInt();
            Bot bot = factory.spawn(world, channel, map, x, y, BotPreset.BEGINNER_LV30);
            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("bot_id", bot.id());
            out.put("name", bot.name());
            // Replace the audit call below with the actual method per notes section H.
            audit.record(name(), null, out);
            return out;
        } catch (BotFactory.DisabledException e) {
            throw new ToolException(McpError.INVALID_REQUEST, "bots disabled");
        } catch (BotManager.AtCapException e) {
            throw new ToolException(McpError.INVALID_REQUEST, e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Run, verify pass**

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/BotSpawnTool.java src/test/java/mcp/tools/BotSpawnToolTest.java
git commit -m "Add MCP tool cosmic.bot.spawn"
```

---

## Task 17: MCP `cosmic.bot.drive`

Mode change + target. Audited.

**Files:**
- Create: `src/main/java/mcp/tools/BotDriveTool.java`
- Create: `src/test/java/mcp/tools/BotDriveToolTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/mcp/tools/BotDriveToolTest.java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.BotConfig;
import mcp.admin.AuditLog;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import server.bot.Bot;
import server.bot.BotManager;
import testutil.Mocks;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BotDriveToolTest {

    @Test
    void setsModeFollowAndTarget() throws Tool.ToolException {
        BotManager mgr = new BotManager(new BotConfig());
        Bot bot = new Bot(Mocks.chr("Bot01"));
        when(bot.character().getId()).thenReturn(-1_000_000);
        when(bot.character().getWorld()).thenReturn(0);
        mgr.register(bot);
        BotDriveTool tool = new BotDriveTool(mgr, Mockito.mock(AuditLog.class));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -1_000_000); args.put("mode", "FOLLOW");
        args.put("target_char_id", 7);
        JsonNode out = tool.call(args);
        assertEquals(Bot.Mode.FOLLOW, bot.mode());
        assertEquals(7, bot.targetCharId());
        assertEquals("FOLLOW", out.get("mode").asText());
    }

    @Test
    void unknownBotIsToolException() {
        BotManager mgr = new BotManager(new BotConfig());
        BotDriveTool tool = new BotDriveTool(mgr, Mockito.mock(AuditLog.class));
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("bot_id", -1).put("mode", "IDLE");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }
}
```

- [ ] **Step 2: Implement** (mirror `BotSpawnTool` shape)

- [ ] **Step 3: Run, verify pass**

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/BotDriveTool.java src/test/java/mcp/tools/BotDriveToolTest.java
git commit -m "Add MCP tool cosmic.bot.drive"
```

---

## Task 18: MCP `cosmic.bot.list`

Read-only. No audit.

**Files:**
- Create: `src/main/java/mcp/tools/BotListTool.java`
- Create: `src/test/java/mcp/tools/BotListToolTest.java`

- [ ] **Step 1: Write failing test**

Lists bots; output JSON has an array of `{bot_id, name, mode, target_char_id, world, channel, map}`. Optional `world` filter.

- [ ] **Step 2: Implement** (mirror `MobWhereTool`)

- [ ] **Step 3: Run, verify pass**

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/tools/BotListTool.java src/test/java/mcp/tools/BotListToolTest.java
git commit -m "Add MCP tool cosmic.bot.list"
```

---

## Task 19: Server boot wiring

This is where the bot subsystem actually starts. Depends on the placer/remover impls that touch real `MapleMap`s, the `WorldView` impl that wraps `Server.getInstance()`, and Task 6's `BotCharacterFactory` body being filled in per investigation notes section B.

**Files:**
- Create: `src/main/java/server/bot/MapPlacer.java` — implements `BotFactory.Placer` and `BotFactory.Remover` over `MapleMap.addPlayer` / `removePlayer`
- Create: `src/main/java/server/bot/ServerWorldView.java` — implements `WorldView` over `Server.getInstance()`
- Modify: `src/main/java/net/server/Server.java` — start `BotManager`, `BotScheduler` after channels are up if `bots.enabled`
- Modify: `src/main/java/mcp/McpServer.java` — register the three bot tools when `mcp.admin_enabled` AND `bots.enabled`
- Modify: `src/main/java/client/bot/BotCharacterFactory.java` — replace `UnsupportedOperationException` with the real call from notes section B
- Create: `src/test/java/server/bot/BotBootTest.java` — small end-to-end: with `bots.enabled=true`, the `@bot spawn` path returns a registered bot and the next scheduler tick runs the brain without exceptions

- [ ] **Step 1: Implement `MapPlacer`** — small adapter that fetches the `MapleMap` from `Server.getInstance().getWorld(world).getChannel(channel).getMapFactory().getMap(mapId)` and calls `addPlayer` / `removePlayer`.

- [ ] **Step 2: Implement `ServerWorldView`**

For each interface method, use the API documented in notes:
- `findCharacterById` → `Server.getInstance().getCharacterFromAllServers(id)` (verify exact name in notes section C/D)
- `nearbyMobIds` → look up the bot's current `MapleMap`, iterate live monsters, filter by squared distance
- `hasItemDropInPickupRadius`, `hasInventorySpaceForNearbyDrops` → likewise via `MapleMap` and `Character` inventory
- `hasPendingPartyInvite` → per notes section C
- `findNearestPortalToMap` → iterate `MapleMap.getPortals()` and pick by `targetMap`
- `isRangedWeapon` → per notes section E
- `mobInAttackRange` → squared-distance check vs an attack radius (small constant — use `cfg.grind_radius / 4` or hardcode 200 px)

- [ ] **Step 3: Replace the placeholder in `BotCharacterFactory.create`** with the real construction from notes section B. Compile and re-run all unit tests added so far.

- [ ] **Step 4: Add boot wiring in `Server.init` (or whatever the existing init method is)**

```java
if (YamlConfig.config.bots.enabled) {
    BotManager botManager = new BotManager(YamlConfig.config.bots);
    BotIdAllocator ids = new BotIdAllocator();
    MapPlacer placer = new MapPlacer();
    BotFactory factory = new BotFactory(YamlConfig.config.bots, botManager, ids, placer, placer::removeFromMap);
    ServerWorldView view = new ServerWorldView();
    DefaultBotBrain brain = new DefaultBotBrain(YamlConfig.config.bots, view);
    BotScheduler scheduler = new BotScheduler(botManager, brain, YamlConfig.config.bots, factory::despawn);
    BotCommand.wire(factory, botManager);
    scheduler.start();

    // Pre-flight: ensure no real character has an id <= -1_000_000.
    BotIdRangeCheck.run(); // throws on collision; aborts startup

    // Register MCP tools if admin enabled
    if (YamlConfig.config.mcp.admin_enabled) {
        // call the existing MCP tool registration site, adding:
        //   new BotSpawnTool(factory, botManager, auditLog)
        //   new BotDriveTool(botManager, auditLog)
        //   new BotListTool(botManager)
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        scheduler.stop();
        for (Bot b : botManager.activeBots()) factory.despawn(b);
    }));
}
```

- [ ] **Step 5: Implement `BotIdRangeCheck`**

```java
// src/main/java/server/bot/BotIdRangeCheck.java
package server.bot;

import tools.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class BotIdRangeCheck {
    public static void run() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT MIN(id) FROM characters");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int min = rs.getInt(1);
                if (!rs.wasNull() && min <= BotIdAllocator.START) {
                    throw new IllegalStateException("characters.id range collides with bot synthetic id range (min=" + min + ")");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("BotIdRangeCheck failed", e);
        }
    }
}
```

- [ ] **Step 6: Add `BotBootTest`**

```java
// src/test/java/server/bot/BotBootTest.java
package server.bot;

import client.bot.BotFactory;
import client.bot.BotPreset;
import config.BotConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotBootTest {

    @Test
    void schedulerTicksWithRegisteredBotWithoutThrowing() {
        BotConfig cfg = new BotConfig(); cfg.enabled = true;
        BotManager mgr = new BotManager(cfg);
        BotIdAllocator ids = new BotIdAllocator();
        BotFactory.Placer placer = (chr,m,x,y)->{};
        BotFactory factory = new BotFactory(cfg, mgr, ids, placer);

        // Use FakeWorldView from earlier brain tests, package-private here.
        FakeWorldView view = new FakeWorldView();
        DefaultBotBrain brain = new DefaultBotBrain(cfg, view);
        BotScheduler s = new BotScheduler(mgr, brain, cfg, factory::despawn);

        // We don't call BotFactory.spawn because BotCharacterFactory still
        // requires the production code path. Instead register a stub bot.
        // (This test focuses on scheduler/brain composition, not on Character build.)
        Bot bot = new Bot(testutil.Mocks.chr("Bot01"));
        org.mockito.Mockito.when(bot.character().getId()).thenReturn(-1_000_000);
        org.mockito.Mockito.when(bot.character().getWorld()).thenReturn(0);
        org.mockito.Mockito.when(bot.character().isAlive()).thenReturn(true);
        org.mockito.Mockito.when(bot.character().getMaxHp()).thenReturn(1000);
        org.mockito.Mockito.when(bot.character().getHp()).thenReturn(1000);
        mgr.register(bot);
        assertDoesNotThrow(() -> s.runOnce(0L));
    }
}
```

- [ ] **Step 7: Run full test suite**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/server/bot/MapPlacer.java src/main/java/server/bot/ServerWorldView.java src/main/java/server/bot/BotIdRangeCheck.java src/main/java/client/bot/BotCharacterFactory.java src/main/java/net/server/Server.java src/main/java/mcp/McpServer.java src/test/java/server/bot/BotBootTest.java
git commit -m "Wire BotManager/Scheduler into Server boot"
```

---

## Task 20: Manual smoke runbook

A small markdown doc capturing the manual steps to verify the bot end-to-end. Goes in `docs/superpowers/notes/`.

**Files:**
- Create: `docs/superpowers/notes/2026-05-08-player-bot-runbook.md`

- [ ] **Step 1: Write the runbook**

```markdown
# Player Bot — Manual Smoke Runbook

## Prereqs

- `bots.enabled: true` in `config.yaml`
- One client logged in to a GM character on world 0, channel 0
- A second client logged in as a regular player in the same channel

## Smoke

1. GM runs `@bot spawn`. Confirm message "spawned BotNN".
2. Second client confirms BotNN is visible on the map.
3. GM runs `@bot follow <regular player name>`. Walk on the regular client; bot follows on the GM's screen.
4. Regular player walks to a portal and changes maps. Bot follows after 1–2 seconds.
5. GM runs `@bot grind` on a map with mobs at the bot's level. Bot attacks nearest mob; mobs die; bot picks up drops.
6. GM kills the bot via PvP-equivalent damage path (or @killmob the bot if a GM command exists). Bot dies, then revives after `revive_delay_ms`.
7. GM invites the bot to a party. Bot joins automatically.
8. Regular player whispers BotNN. Confirm no "user is offline" reply appears.
9. GM runs `@bot despawn BotNN`. Bot disappears from second client's screen.

## MCP smoke (optional, if `mcp.admin_enabled: true`)

```
cosmic.bot.spawn  {world:0, channel:0, map:100000000, x:0, y:0}
cosmic.bot.list   {world:0}
cosmic.bot.drive  {bot_id:<id>, mode:"GRIND"}
```
Confirm each call returns expected JSON and an `mcp_admin_audit` row exists for the spawn and drive.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/notes/2026-05-08-player-bot-runbook.md
git commit -m "Add player-bot manual smoke runbook"
```

---

## Self-review

**Spec coverage:**
- A1 (visible) ↔ Task 19 (`MapPlacer.placeOnMap` → `MapleMap.addPlayer` broadcasts spawn)
- A2 (walk/jump) ↔ Tasks 11/12 produce `STEP_TOWARD_*`; the `execute` half is filled in Task 19's `ServerWorldView`-paired execution code (see open item below)
- A3 (portals) ↔ Task 11 + Task 19's portal walk
- B1 (auto-attack) ↔ Task 12
- B3 (HP/MP/die/revive) ↔ Tasks 9, 10
- B4 (loot) ↔ Task 13
- C1 (party join) ↔ Task 14
- C2 (follow) ↔ Task 11
- D2 (ephemeral) ↔ Task 6 builds in-memory only; no DB writes anywhere

**Open gap:** Tasks 9–14 cover the `decide` half of the brain. The `execute` half — actually translating `BotAction` into packets / state changes (`STEP_TOWARD_TARGET` → emit movement packet, `ATTACK_MELEE` → emit attack packet + apply damage, `USE_HP_POT` → call existing pot-use path, `WAIT_REVIVE` → schedule revive, `ACCEPT_PARTY_INVITE` → call party-accept path) — relies on APIs documented in notes sections D, E, F. Task 19 is the natural place for this and explicitly depends on the notes. Add a sub-task to Task 19: implement `DefaultBotBrain.execute` for each of the 11 non-IDLE actions, with one targeted test per action that asserts the corresponding side effect is invoked (use Mockito spies on the `WorldView` / `MapleMap` interactions). Without this, the bot decides but doesn't act.

**Placeholder scan:** Two tasks reference investigation notes sections (Task 6's `BotCharacterFactory` body, Task 19's execute branch). Both are bounded — they specify exactly which notes section to consult and what artifact to produce. The notes doc itself (Task 1) is concrete and produces fillable code references. Acceptable.

**Type consistency:** `BotAction` enum values are referenced consistently (`USE_HP_POT`, `STEP_TOWARD_TARGET`, etc.). `Bot.Mode` enum values consistent. `BotManager.AtCapException` named the same in registration check and command-side handling.

**Fix added inline:** Adding the missing brain-execute work to Task 19.

---

## Task 19 — addendum: `DefaultBotBrain.execute`

Add as a sub-section of Task 19, after Step 5 (`BotIdRangeCheck`), before Step 6 (`BotBootTest`):

- [ ] **Step 5b: Implement `execute` per action**

For each `BotAction` non-IDLE value, add a branch in `DefaultBotBrain.execute(Bot, BotAction, long)`. Each branch calls a method on a new `BotActuator` interface (production impl wraps `MapleMap` / `Character` / packet creators per notes sections D/E/F). The actuator interface mirrors `WorldView` for testability.

```java
// src/main/java/server/bot/BotActuator.java
package server.bot;

public interface BotActuator {
    void useHpPot(Bot bot);
    void useMpPot(Bot bot);
    void retreatStep(Bot bot);
    void scheduleRevive(Bot bot, int delayMs);
    void acceptPartyInvite(Bot bot);
    void walkToPortal(Bot bot, int targetMapId);
    void stepTowardTarget(Bot bot, int targetCharId);
    void stepTowardMob(Bot bot, int mobId);
    void attackMelee(Bot bot, int mobId);
    void attackRanged(Bot bot, int mobId);
    void pickup(Bot bot);
}
```

Modify `DefaultBotBrain` constructor to accept `BotActuator` and route `execute(...)` to it. Add tests in `DefaultBotBrainTest` that use a Mockito spy actuator and assert each action triggers the corresponding actuator method.

- [ ] **Step 5c: Implement production `MapActuator implements BotActuator`**

Each method calls the existing server method documented in notes sections D/E/F. Commit only after `mvn test` passes for all tests.

---

# Plan complete

Saved to `docs/superpowers/plans/2026-05-08-player-bot.md`.
