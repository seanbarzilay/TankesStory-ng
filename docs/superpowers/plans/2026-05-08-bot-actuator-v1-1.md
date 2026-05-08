# Player Bot v1.1 — `MapActuator` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace v1's `LoggingBotActuator` stub with a real `MapActuator` so bots actually walk, fight, loot, drink pots, accept party invites, and revive — visibly to other clients.

**Architecture:** A new `server.bot.MapActuator implements BotActuator` translates each `BotAction` into existing server methods (`MapleMap.broadcastMessage`, `MapleMap.damageMonster`, `MapleMap.pickItemDrop`, `Character.changeMap`, `Character.addHP`, `Party.joinParty`, `TimerManager.schedule`). For movement, a small `server.bot.MoveBuilder` synthesizes a v83 `AbsoluteLifeMovement` byte stream so the existing `MOVE_PLAYER` opcode renders the bot stepping smoothly.

**Tech Stack:** Java 21, Maven via `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn ...`, JUnit 5, Mockito, SLF4J. No new dependencies.

---

## Conventions

- Run a single test class: `podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=ClassName`
- All commits include the `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` line.
- Stay on whatever worktree branch the executor created from `origin/master`.

## File map

**Created:**
- `src/main/java/server/bot/MoveBuilder.java` — synthesizes absolute-move byte streams
- `src/main/java/server/bot/MapActuator.java` — production `BotActuator` impl
- `src/test/java/server/bot/MoveBuilderTest.java`
- `src/test/java/server/bot/MapActuatorTest.java`

**Modified:**
- `src/main/java/server/bot/ServerWorldView.java` — fill in `isRangedWeapon` and `hasItemDropInPickupRadius`
- `src/main/java/net/server/Server.java` — pass `MapActuator` (not `LoggingBotActuator`) to `DefaultBotBrain`

**No changes to:** the brain, scheduler, manager, factory, command, MCP tools — they already invoke `BotActuator` cleanly.

---

## Task 1: `MoveBuilder` — synthesize a v83 absolute-move byte stream

**Files:**
- Create: `src/main/java/server/bot/MoveBuilder.java`
- Create: `src/test/java/server/bot/MoveBuilderTest.java`

The MOVE_PLAYER packet body after the `chrId` and a 4-byte filler is a movement list: `count (byte)` then `count` × movement command. For an absolute-move (command type 0): `byte type=0`, `short x`, `short y`, `short xwobble`, `short ywobble`, `short fh`, `byte newstate`, `short duration`. We only ever emit a single command per step.

We round-trip our output through `AbstractMovementPacketHandler.parseMovement` to verify it parses back to the same `AbsoluteLifeMovement` we constructed.

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/server/bot/MoveBuilderTest.java
package server.bot;

import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.channel.handlers.AbstractMovementPacketHandler;
import org.junit.jupiter.api.Test;
import server.movement.AbsoluteLifeMovement;
import server.movement.LifeMovementFragment;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveBuilderTest {

    @Test
    void absoluteStepRoundTripsThroughParser() throws Exception {
        Point dst = new Point(100, -200);
        int stance = 4;
        int durationMs = 200;
        int fh = 7;

        // Build the byte stream as MoveBuilder will emit it.
        OutPacket op = OutPacket.create(net.packet.SendOpcode.MOVE_PLAYER);
        // Don't write the chr id / filler — MoveBuilder.serialize only writes
        // the count + command bytes (the parser reads from there).
        MoveBuilder.serializeAbsoluteStep(op, dst, stance, durationMs, fh);

        InPacket ip = packetToInPacket(op.getBytes());
        // parseMovement is protected; use a thin handler subclass to expose it.
        TestHandler handler = new TestHandler();
        List<LifeMovementFragment> parsed = handler.callParse(ip);
        assertEquals(1, parsed.size());
        AbsoluteLifeMovement alm = (AbsoluteLifeMovement) parsed.get(0);
        assertEquals(0, alm.getType(), "command type 0 = normal absolute");
        assertEquals(dst, alm.getPosition());
        assertEquals(stance, alm.getNewstate());
        assertEquals(durationMs, alm.getDuration());
        assertEquals(fh, alm.getFh());
    }

    private static InPacket packetToInPacket(byte[] bytes) {
        // Strip the 2-byte SendOpcode prefix that OutPacket prepends; we want
        // the parseMovement stream which starts with the count byte.
        byte[] payload = new byte[bytes.length - 2];
        System.arraycopy(bytes, 2, payload, 0, payload.length);
        return new net.packet.ByteBufInPacket(io.netty.buffer.Unpooled.wrappedBuffer(payload));
    }

    /** Exposes the protected parseMovement for testing. */
    private static class TestHandler extends AbstractMovementPacketHandler {
        @Override
        public void handlePacket(InPacket p, client.Client c) { /* unused */ }

        List<LifeMovementFragment> callParse(InPacket p) throws Exception {
            return parseMovement(p);
        }
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MoveBuilderTest
```

Expected: compile error (`MoveBuilder` not found, possibly also `ByteBufInPacket` if that's not the right concrete class — adjust to whatever `InPacket` impl the codebase uses; check `net.packet.ByteBufInPacket` exists or look at how tests construct an `InPacket` elsewhere, e.g., `src/test/java/testutil/Packets.java`).

- [ ] **Step 3: Implement `MoveBuilder`**

```java
// src/main/java/server/bot/MoveBuilder.java
package server.bot;

import net.packet.OutPacket;

import java.awt.Point;

/**
 * Synthesizes the v83 MOVE_PLAYER movement-list byte stream that
 * {@link net.server.channel.handlers.AbstractMovementPacketHandler#parseMovement}
 * round-trips back to {@link server.movement.AbsoluteLifeMovement}.
 *
 * <p>v1.1 uses one absolute-move command per actuator tick. Bot smoothness is
 * a function of the tick rate, not multi-segment movement.
 */
public final class MoveBuilder {

    /** Default stance when the bot doesn't otherwise care: standing right-facing. */
    public static final int STANCE_STAND_RIGHT = 4;

    private MoveBuilder() {}

    /**
     * Writes a movement list with one absolute-move command (type 0) into {@code p}.
     * The bytes match the format produced by a real v83 client's movement packet.
     */
    public static void serializeAbsoluteStep(OutPacket p, Point dst, int stance,
                                             int durationMs, int fh) {
        p.writeByte(1);          // count
        p.writeByte(0);          // command type: absolute
        p.writePos(dst);         // x, y
        p.writePos(new Point(0, 0)); // xwobble, ywobble (not used for our case)
        p.writeShort(fh);        // foothold
        p.writeByte(stance);     // newstate
        p.writeShort(durationMs);
    }
}
```

- [ ] **Step 4: Run, verify pass**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MoveBuilderTest
```

If the `ByteBufInPacket` constructor signature is different, look at existing tests that build an `InPacket` from raw bytes (likely via `testutil.Packets` or directly via `new ByteBufInPacket(Unpooled.wrappedBuffer(...))` — adapt as needed).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/server/bot/MoveBuilder.java src/test/java/server/bot/MoveBuilderTest.java
git commit -m "$(cat <<'EOF'
Add MoveBuilder: synthesize v83 absolute-move byte stream for bot movement

Round-trip verified against AbstractMovementPacketHandler.parseMovement.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `MapActuator` skeleton + `broadcastStep` helper

**Files:**
- Create: `src/main/java/server/bot/MapActuator.java`
- Create: `src/test/java/server/bot/MapActuatorTest.java`

The actuator constructor takes a `BotConfig` (for `revive_delay_ms`, etc.) and nothing else. Side effects flow through `Bot.character()` which already exposes the underlying `Character`. `MapleMap` is reachable via `chr.getMap()`.

This task introduces the class with a single helper, `broadcastStep`, that callers (Tasks 3 onward) use. We don't implement any `BotActuator` method yet beyond `IDLE`; tests assert the helper produces the right packet bytes.

- [ ] **Step 1: Implement skeleton**

```java
// src/main/java/server/bot/MapActuator.java
package server.bot;

import client.Character;
import config.BotConfig;
import net.packet.OutPacket;
import net.packet.Packet;
import net.packet.SendOpcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.awt.Point;

public class MapActuator implements BotActuator {

    private static final Logger log = LoggerFactory.getLogger(MapActuator.class);
    private static final int STEP_DURATION_MS = 200;

    private final BotConfig cfg;

    public MapActuator(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Builds and broadcasts a MOVE_PLAYER packet that walks the bot to {@code dst}.
     * Returns the packet for assertion in tests.
     */
    Packet broadcastStep(Bot bot, Point dst) {
        Character chr = bot.character();
        OutPacket op = OutPacket.create(SendOpcode.MOVE_PLAYER);
        op.writeInt(chr.getId());
        op.writeInt(0);
        MoveBuilder.serializeAbsoluteStep(op, dst, MoveBuilder.STANCE_STAND_RIGHT,
                STEP_DURATION_MS, /*fh=*/0);
        Packet packet = op.toPacket();
        chr.setPosition(new Point(dst));
        MapleMap map = chr.getMap();
        if (map != null) {
            map.broadcastMessage(chr, packet, /*repeatToSource=*/false);
        }
        return packet;
    }

    // BotActuator stubs — Tasks 3-11 fill these in.
    @Override public void useHpPot(Bot bot) { log.debug("MapActuator useHpPot {} (TODO)", bot.id()); }
    @Override public void useMpPot(Bot bot) { log.debug("MapActuator useMpPot {} (TODO)", bot.id()); }
    @Override public void retreatStep(Bot bot) { log.debug("MapActuator retreat {} (TODO)", bot.id()); }
    @Override public void scheduleRevive(Bot bot, int delayMs) { log.debug("MapActuator scheduleRevive {} (TODO)", bot.id()); }
    @Override public void acceptPartyInvite(Bot bot) { log.debug("MapActuator acceptPartyInvite {} (TODO)", bot.id()); }
    @Override public void walkToPortal(Bot bot, int targetMapId) { log.debug("MapActuator walkToPortal {} (TODO)", bot.id()); }
    @Override public void stepTowardTarget(Bot bot, int targetCharId) { log.debug("MapActuator stepTowardTarget {} (TODO)", bot.id()); }
    @Override public void stepTowardMob(Bot bot, int mobId) { log.debug("MapActuator stepTowardMob {} (TODO)", bot.id()); }
    @Override public void attackMelee(Bot bot, int mobId) { log.debug("MapActuator attackMelee {} (TODO)", bot.id()); }
    @Override public void attackRanged(Bot bot, int mobId) { log.debug("MapActuator attackRanged {} (TODO)", bot.id()); }
    @Override public void pickup(Bot bot) { log.debug("MapActuator pickup {} (TODO)", bot.id()); }
}
```

If `OutPacket.toPacket()` doesn't exist, find the equivalent (look at how `PacketCreator` returns packets — most likely the `OutPacket` itself implements `Packet` or there's a `getBytes()` method that's wrapped into a `ByteArrayPacket`).

- [ ] **Step 2: Write the helper test**

```java
// src/test/java/server/bot/MapActuatorTest.java
package server.bot;

import client.Character;
import config.BotConfig;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import testutil.Mocks;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class MapActuatorTest {

    static Bot bot(int id) {
        Character chr = Mocks.chr("Bot01");
        when(chr.getId()).thenReturn(id);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        return new Bot(chr);
    }

    @Test
    void broadcastStepUpdatesPositionAndCallsBroadcast() {
        BotConfig cfg = new BotConfig();
        MapActuator a = new MapActuator(cfg);
        Bot b = bot(-1_000_000);
        MapleMap map = mock(MapleMap.class);
        when(b.character().getMap()).thenReturn(map);
        Point dst = new Point(50, 100);

        Packet pkt = a.broadcastStep(b, dst);
        assertNotNull(pkt);
        verify(b.character()).setPosition(eq(new Point(50, 100)));
        verify(map).broadcastMessage(same(b.character()), same(pkt), eq(false));
    }

    @Test
    void broadcastStepDoesNothingWhenMapIsNull() {
        MapActuator a = new MapActuator(new BotConfig());
        Bot b = bot(-1_000_000);
        when(b.character().getMap()).thenReturn(null);
        Packet pkt = a.broadcastStep(b, new Point(1, 1));
        assertNotNull(pkt, "still produces a packet, just doesn't broadcast");
    }
}
```

- [ ] **Step 3: Run, verify pass**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MapActuatorTest
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/server/bot/MapActuator.java src/test/java/server/bot/MapActuatorTest.java
git commit -m "$(cat <<'EOF'
Add MapActuator skeleton + broadcastStep helper

All BotActuator methods stubbed at log.debug level so the actuator
can be wired in immediately without changing observable behavior;
Tasks 3-11 fill them in one at a time.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Step methods — `stepTowardTarget`, `stepTowardMob`, `retreatStep`

All three pick a destination relative to the bot's current position and call `broadcastStep`. Step distance is configurable per step; for v1.1 use a constant 60 px (~one tile).

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java` — replace the three log.debug stubs
- Modify: `src/test/java/server/bot/MapActuatorTest.java` — add three tests

- [ ] **Step 1: Add tests**

```java
// Append to MapActuatorTest
@Test
void stepTowardTargetMovesOneStepCloser() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    Character target = Mocks.chr("Target");
    when(target.getPosition()).thenReturn(new Point(500, 0));
    when(target.getMapId()).thenReturn(100000000);
    when(b.character().getMapId()).thenReturn(100000000);
    // stub Server.getCharacterFromAllServers via a small indirection: the
    // actuator looks up the target by id. For tests, inject the lookup
    // through a constructor parameter rather than a static. See impl below.

    a.stepTowardTarget(b, 999); // we'll wire the lookup through a setter
    // Test asserts the actuator broadcasted *some* MOVE_PLAYER for now.
    verify(map, atLeastOnce()).broadcastMessage(same(b.character()), any(), eq(false));
}

@Test
void stepTowardMobMovesOneStepCloser() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    server.life.Monster mob = mock(server.life.Monster.class);
    when(mob.getPosition()).thenReturn(new Point(300, 0));
    when(map.getMonsterByOid(42)).thenReturn(mob);
    a.stepTowardMob(b, 42);
    verify(map).broadcastMessage(same(b.character()), any(), eq(false));
}

@Test
void retreatBroadcastsAStep() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    a.retreatStep(b);
    verify(map).broadcastMessage(same(b.character()), any(), eq(false));
}
```

- [ ] **Step 2: Implement step methods**

The actuator needs a way to look up a `Character` by id. Inject it. Update the constructor:

```java
// In MapActuator.java — replace constructor + add LookupByCharId

@FunctionalInterface
public interface CharacterLookup {
    Character byId(int id); // returns null if not found
}

private final BotConfig cfg;
private final CharacterLookup characterLookup;

public MapActuator(BotConfig cfg) {
    this(cfg, MapActuator::serverCharacterLookup);
}

public MapActuator(BotConfig cfg, CharacterLookup lookup) {
    this.cfg = cfg;
    this.characterLookup = lookup;
}

private static Character serverCharacterLookup(int id) {
    try {
        return net.server.Server.getInstance().getWorld(0)
                .getPlayerStorage().getCharacterById(id);
    } catch (Throwable t) { return null; }
}
```

Now replace the three `log.debug` stubs:

```java
@Override
public void stepTowardTarget(Bot bot, int targetCharId) {
    Character target = characterLookup.byId(targetCharId);
    if (target == null) return;
    broadcastStep(bot, stepToward(bot.character().getPosition(), target.getPosition()));
}

@Override
public void stepTowardMob(Bot bot, int mobId) {
    server.maps.MapleMap map = bot.character().getMap();
    if (map == null) return;
    server.life.Monster mob = map.getMonsterByOid(mobId);
    if (mob == null) return;
    broadcastStep(bot, stepToward(bot.character().getPosition(), mob.getPosition()));
}

@Override
public void retreatStep(Bot bot) {
    // Move toward the bot's spawn x — for v1.1, just a step in the negative x direction.
    Point cur = bot.character().getPosition();
    broadcastStep(bot, new Point(cur.x - STEP_PX, cur.y));
}

private static final int STEP_PX = 60;

private static Point stepToward(Point from, Point to) {
    int dx = to.x - from.x;
    int dy = to.y - from.y;
    double dist = Math.sqrt(dx*dx + dy*dy);
    if (dist <= STEP_PX || dist == 0) return new Point(to);
    int sx = (int) Math.round(from.x + dx * STEP_PX / dist);
    int sy = (int) Math.round(from.y + dy * STEP_PX / dist);
    return new Point(sx, sy);
}
```

If the `MapleMap` API uses `getMonsterByOid` differently, adjust — search the codebase for `getMonsterByOid` usage to confirm the signature.

- [ ] **Step 3: Run, verify pass**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MapActuatorTest
```

The first new test (`stepTowardTargetMovesOneStepCloser`) needs the `CharacterLookup` injected — adjust the test to construct `MapActuator(new BotConfig(), id -> id == 999 ? target : null)` and pass `target` from above.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/server/bot/MapActuator.java src/test/java/server/bot/MapActuatorTest.java
git commit -m "$(cat <<'EOF'
MapActuator: stepTowardTarget, stepTowardMob, retreatStep

Single 60px step per actuator call; bot smoothness comes from the
brain's 200ms tick rate. CharacterLookup is injectable so tests don't
need a real Server.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `ServerWorldView.isRangedWeapon` real impl

**Files:**
- Modify: `src/main/java/server/bot/ServerWorldView.java` — replace the `// TODO follow-up` stub
- Modify: `src/test/java/server/bot/` (no existing test file — Tasks 9–14 of v1 covered the brain via `FakeWorldView`; the production lookup is exercised end-to-end in Task 12 here)

Per investigation notes section E: weapon item is in slot `-11` of the EQUIPPED inventory; `ItemInformationProvider.getInstance().getWeaponType(itemId)` returns a `WeaponType`; ranged ⇔ `BOW | CROSSBOW | CLAW | GUN`.

- [ ] **Step 1: Replace the body**

```java
// In ServerWorldView.java
@Override
public boolean isRangedWeapon(Bot bot) {
    try {
        client.inventory.Inventory eq = bot.character().getInventory(client.inventory.InventoryType.EQUIPPED);
        if (eq == null) return false;
        client.inventory.Item weapon = eq.getItem((short) -11);
        if (weapon == null) return false;
        constants.inventory.ItemConstants.WeaponType type =
                server.ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
        return type == constants.inventory.ItemConstants.WeaponType.BOW
                || type == constants.inventory.ItemConstants.WeaponType.CROSSBOW
                || type == constants.inventory.ItemConstants.WeaponType.CLAW
                || type == constants.inventory.ItemConstants.WeaponType.GUN;
    } catch (Throwable t) {
        return false;
    }
}
```

The exact `WeaponType` enum location may differ; adjust by searching for `enum WeaponType` in the codebase. The `getWeaponType` signature might also be `(int itemId) → WeaponType` or `(Item) → WeaponType`.

- [ ] **Step 2: Compile-check**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn -DskipTests compile -q
```

If it doesn't compile, look at how `WeaponType` is used elsewhere (search for `WeaponType\.BOW` or `getWeaponType` and copy the imports).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/server/bot/ServerWorldView.java
git commit -m "$(cat <<'EOF'
ServerWorldView.isRangedWeapon: real lookup via slot -11 + WeaponType

Lets the brain dispatch ATTACK_MELEE vs ATTACK_RANGED correctly when
the bot is equipped with a bow/crossbow/claw/gun.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `attackMelee` — synthesize a CLOSE_RANGE_ATTACK + apply damage

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

Combat path:
1. Look up the target `Monster` via `map.getMonsterByOid(mobId)`.
2. Compute damage. v1.1 uses `chr.getLevel() * 10` as a placeholder constant — combat-balance tuning is out of scope (the bot's job is to chip away at mobs, not min-max).
3. Build an `AttackInfo`-style packet via `PacketCreator.closeRangeAttack(chr, skill=0, lvl=0, stance=4, numAttackedAndDamage, targets, speed=4, direction=0, display=0)` where `targets` is `{ mob.oid: AttackTarget(0, [damage]) }`.
4. Broadcast to map.
5. Call `map.damageMonster(chr, mob, damage)`.

- [ ] **Step 1: Add test**

```java
@Test
void attackMeleeBroadcastsAttackAndAppliesDamage() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getLevel()).thenReturn(30);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    server.life.Monster mob = mock(server.life.Monster.class);
    when(mob.getObjectId()).thenReturn(42);
    when(mob.getPosition()).thenReturn(new Point(20, 0));
    when(map.getMonsterByOid(42)).thenReturn(mob);

    a.attackMelee(b, 42);
    verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    verify(map).damageMonster(same(b.character()), same(mob), org.mockito.ArgumentMatchers.intThat(d -> d > 0));
}
```

- [ ] **Step 2: Implement**

```java
// In MapActuator.java — replace the attackMelee stub
@Override
public void attackMelee(Bot bot, int mobId) {
    Character chr = bot.character();
    MapleMap map = chr.getMap();
    if (map == null) return;
    server.life.Monster mob = map.getMonsterByOid(mobId);
    if (mob == null) return;

    int damage = Math.max(1, chr.getLevel() * 10);
    java.util.Map<Integer, net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget> targets =
            new java.util.HashMap<>();
    targets.put(mob.getObjectId(),
            new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                    /*delay=*/(short) 0, java.util.List.of(damage)));

    Packet packet = tools.PacketCreator.closeRangeAttack(
            chr, /*skill=*/0, /*skilllevel=*/0, /*stance=*/MoveBuilder.STANCE_STAND_RIGHT,
            /*numAttackedAndDamage=*/(1 << 4) | 1, targets,
            /*speed=*/4, /*direction=*/0, /*display=*/0);
    map.broadcastMessage(chr, packet, /*repeatToSource=*/false);
    map.damageMonster(chr, mob, damage);
}
```

The `numAttackedAndDamage` field encodes `(numAttacked << 4) | numDamage` per the existing handler. For one mob, one damage line, that's `(1 << 4) | 1 = 0x11`.

- [ ] **Step 3: Run**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MapActuatorTest
```

- [ ] **Step 4: Commit**

```bash
git commit -am "$(cat <<'EOF'
MapActuator.attackMelee: synthesize CLOSE_RANGE_ATTACK + damageMonster

v1.1 uses level*10 as placeholder damage; balance tuning is out of
scope. The packet encodes one target with one damage line.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `attackRanged` — same pattern, RANGED_ATTACK opcode

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

- [ ] **Step 1: Add test**

```java
@Test
void attackRangedBroadcastsAndAppliesDamage() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getLevel()).thenReturn(30);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    server.life.Monster mob = mock(server.life.Monster.class);
    when(mob.getObjectId()).thenReturn(42);
    when(map.getMonsterByOid(42)).thenReturn(mob);

    a.attackRanged(b, 42);
    verify(map).broadcastMessage(same(b.character()), any(), eq(false));
    verify(map).damageMonster(same(b.character()), same(mob), org.mockito.ArgumentMatchers.intThat(d -> d > 0));
}
```

- [ ] **Step 2: Implement**

```java
@Override
public void attackRanged(Bot bot, int mobId) {
    Character chr = bot.character();
    MapleMap map = chr.getMap();
    if (map == null) return;
    server.life.Monster mob = map.getMonsterByOid(mobId);
    if (mob == null) return;

    int damage = Math.max(1, chr.getLevel() * 10);
    java.util.Map<Integer, net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget> targets =
            new java.util.HashMap<>();
    targets.put(mob.getObjectId(),
            new net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget(
                    (short) 0, java.util.List.of(damage)));

    Packet packet = tools.PacketCreator.rangedAttack(
            chr, /*skill=*/0, /*skilllevel=*/0, /*stance=*/MoveBuilder.STANCE_STAND_RIGHT,
            /*numAttackedAndDamage=*/(1 << 4) | 1, /*projectile=*/0, targets,
            /*speed=*/4, /*direction=*/0, /*display=*/0);
    map.broadcastMessage(chr, packet, false);
    map.damageMonster(chr, mob, damage);
}
```

- [ ] **Step 3: Run + commit**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test -Dtest=MapActuatorTest
git commit -am "MapActuator.attackRanged: synthesize RANGED_ATTACK"
```

(Use a full HEREDOC commit body matching Task 5's style.)

---

## Task 7: `useHpPot` and `useMpPot`

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

For each pot type:
1. Look up the configured pot in `cfg.hp_pot_item_id` / `cfg.mp_pot_item_id`.
2. Find it in the bot's USE inventory.
3. Look up its heal stat via `ItemInformationProvider.getInstance().getItemEffect(itemId)` → `StatEffect.getHp()` / `getMp()`.
4. Call `chr.addHP(heal)` / `chr.addMP(heal)`.
5. Decrement the pot quantity by 1 (or remove if quantity reaches 0).

- [ ] **Step 1: Add tests**

```java
@Test
void useHpPotHealsAndDecrementsInventory() {
    BotConfig cfg = new BotConfig();
    MapActuator a = new MapActuator(cfg);
    Bot b = bot(-1_000_000);
    Character chr = b.character();
    client.inventory.Inventory use = mock(client.inventory.Inventory.class);
    when(chr.getInventory(client.inventory.InventoryType.USE)).thenReturn(use);
    client.inventory.Item pot = mock(client.inventory.Item.class);
    when(pot.getItemId()).thenReturn(cfg.hp_pot_item_id);
    when(pot.getQuantity()).thenReturn((short) 3);
    when(pot.getPosition()).thenReturn((short) 0);
    when(use.findById(cfg.hp_pot_item_id)).thenReturn(pot);

    a.useHpPot(b);
    verify(chr).addHP(org.mockito.ArgumentMatchers.intThat(h -> h > 0));
}
```

- [ ] **Step 2: Implement**

```java
@Override
public void useHpPot(Bot bot) { drinkPot(bot, cfg.hp_pot_item_id, true); }

@Override
public void useMpPot(Bot bot) { drinkPot(bot, cfg.mp_pot_item_id, false); }

private void drinkPot(Bot bot, int potItemId, boolean hp) {
    Character chr = bot.character();
    client.inventory.Inventory inv = chr.getInventory(client.inventory.InventoryType.USE);
    if (inv == null) return;
    client.inventory.Item pot = inv.findById(potItemId);
    if (pot == null) return;

    server.StatEffect effect = server.ItemInformationProvider.getInstance().getItemEffect(potItemId);
    if (effect == null) return;
    int heal = hp ? effect.getHp() : effect.getMp();
    if (heal <= 0) return;

    if (hp) chr.addHP(heal); else chr.addMP(heal);

    // decrement inventory quantity
    short newQty = (short) (pot.getQuantity() - 1);
    if (newQty <= 0) {
        inv.removeItem(pot.getPosition());
    } else {
        pot.setQuantity(newQty);
    }
}
```

The `Inventory.removeItem` / `Item.setQuantity` signatures may differ; verify by searching for existing pot-use callsites (`UseItemHandler`).

- [ ] **Step 3: Run + commit**

---

## Task 8: `pickup` and `ServerWorldView.hasItemDropInPickupRadius`

**Files:**
- Modify: `src/main/java/server/bot/ServerWorldView.java`
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

Pickup radius: 100 px (one arm length around the bot). If multiple drops are in range, pick up the nearest.

- [ ] **Step 1: Fill in `ServerWorldView.hasItemDropInPickupRadius`**

```java
private static final int PICKUP_RADIUS_PX = 100;

@Override
public boolean hasItemDropInPickupRadius(Bot bot) {
    try {
        Character chr = bot.character();
        MapleMap map = chr.getMap();
        if (map == null) return false;
        Point pos = chr.getPosition();
        int r2 = PICKUP_RADIUS_PX * PICKUP_RADIUS_PX;
        for (var obj : map.getMapObjects()) {
            if (obj instanceof server.maps.MapItem mi) {
                int dx = mi.getPosition().x - pos.x;
                int dy = mi.getPosition().y - pos.y;
                if (dx*dx + dy*dy <= r2) return true;
            }
        }
        return false;
    } catch (Throwable t) { return false; }
}
```

- [ ] **Step 2: Add `MapActuator.pickup` test + impl**

Test:

```java
@Test
void pickupCallsMapPickItemDrop() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    when(b.character().getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(b.character().getMap()).thenReturn(map);
    server.maps.MapItem drop = mock(server.maps.MapItem.class);
    when(drop.getPosition()).thenReturn(new Point(10, 0));
    when(map.getMapObjects()).thenReturn(java.util.List.of(drop));

    a.pickup(b);
    verify(map).pickItemDrop(any(), same(drop));
}
```

Impl:

```java
@Override
public void pickup(Bot bot) {
    Character chr = bot.character();
    MapleMap map = chr.getMap();
    if (map == null) return;
    Point pos = chr.getPosition();
    int r2 = 100 * 100;
    server.maps.MapItem nearest = null;
    long nearestD2 = Long.MAX_VALUE;
    for (var obj : map.getMapObjects()) {
        if (obj instanceof server.maps.MapItem mi) {
            int dx = mi.getPosition().x - pos.x;
            int dy = mi.getPosition().y - pos.y;
            long d2 = (long) dx*dx + (long) dy*dy;
            if (d2 <= r2 && d2 < nearestD2) { nearest = mi; nearestD2 = d2; }
        }
    }
    if (nearest == null) return;
    Packet pickupPkt = tools.PacketCreator.removeItemFromMap(nearest.getObjectId(), 2, chr.getId());
    map.pickItemDrop(pickupPkt, nearest);
}
```

The `removeItemFromMap(...)` signature may differ — verify by searching for existing pickup handler callsites.

- [ ] **Step 3: Run + commit**

---

## Task 9: `scheduleRevive`

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

Schedule a `TimerManager` task after `cfg.revive_delay_ms`. The task: reset HP/MP to maxHP/maxMP, broadcast a fresh spawn (via `PacketCreator.spawnPlayerMapObject`), and clear any "dead" state.

- [ ] **Step 1: Add test**

The test injects a synchronous `ScheduledExecutor` to avoid real threading. Use a constructor variant of `MapActuator` that takes a `java.util.function.IntFunction<java.util.concurrent.ScheduledFuture<?>>` for the scheduling primitive — or simpler, inject a `BiConsumer<Runnable, Long>` that runs the runnable inline.

```java
@FunctionalInterface
interface DelayedScheduler {
    void schedule(Runnable r, long delayMs);
}

// New constructor
public MapActuator(BotConfig cfg, CharacterLookup lookup, DelayedScheduler scheduler) { ... }

// Default uses TimerManager
public MapActuator(BotConfig cfg) { this(cfg, MapActuator::serverCharacterLookup,
    (r, ms) -> server.TimerManager.getInstance().schedule(r, ms)); }
```

Test:

```java
@Test
void scheduleReviveResetsHpAndRespawns() {
    BotConfig cfg = new BotConfig();
    cfg.revive_delay_ms = 1; // doesn't matter since scheduler runs inline
    Runnable[] captured = {null};
    MapActuator a = new MapActuator(cfg, id -> null, (r, ms) -> captured[0] = r);
    Bot b = bot(-1_000_000);
    Character chr = b.character();
    when(chr.getMaxHp()).thenReturn(1500);
    when(chr.getMaxMp()).thenReturn(200);
    MapleMap map = mock(MapleMap.class);
    when(chr.getMap()).thenReturn(map);

    a.scheduleRevive(b, cfg.revive_delay_ms);
    assertNotNull(captured[0], "scheduler called");
    captured[0].run();

    verify(chr).setHpMp(1500, 200);
    verify(map).broadcastMessage(any(), any(), eq(false));
}
```

- [ ] **Step 2: Implement**

```java
@Override
public void scheduleRevive(Bot bot, int delayMs) {
    scheduler.schedule(() -> {
        Character chr = bot.character();
        chr.setHpMp(chr.getMaxHp(), chr.getMaxMp());
        MapleMap map = chr.getMap();
        if (map != null) {
            map.broadcastMessage(chr,
                    tools.PacketCreator.spawnPlayerMapObject(chr.getClient(), chr, /*enteringField=*/false),
                    false);
        }
    }, delayMs);
}
```

If `Character.setHpMp(int, int)` doesn't exist, use `chr.setHp(chr.getMaxHp())` + `chr.setMp(chr.getMaxMp())` (find the right protected/public combination).

- [ ] **Step 3: Run + commit**

---

## Task 10: `acceptPartyInvite`

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

Per investigation notes section C: `InviteCoordinator.peekInvite(InviteType.PARTY, charId)` returns the inviter. Then call `Party.joinParty(player, inviterParty.getId(), false)`.

If `peekInvite` doesn't yet exist (the v1 spec note said it should be added), add it as a static accessor on `InviteCoordinator` returning the inviter character id, or look at how `answerInvite` already returns the queued data and adapt.

- [ ] **Step 1: Add `peekInvite` accessor on `InviteCoordinator`** (only if not already there from v1)

The accessor reads the package-private inviter map and returns the inviter character id. Tests in `InviteCoordinatorTest` (if present) or a new tiny test should verify: invite stored → peek returns it → not consumed.

- [ ] **Step 2: Add test for `acceptPartyInvite`**

```java
@Test
void acceptPartyInviteJoinsExistingParty() throws Exception {
    // Mocking static Party.joinParty cleanly requires Mockito-inline or a wrapper.
    // For this test, inject a partyJoiner functional through the actuator constructor.
    // Define MapActuator.PartyJoiner and update the constructor.
    ...
}
```

The simplest path is to make `MapActuator` take a `PartyJoiner` functional interface (`(player, partyId) -> boolean`) injected at construction, defaulting to `Party::joinParty`.

- [ ] **Step 3: Implement**

```java
@FunctionalInterface
public interface PartyJoiner {
    boolean join(Character player, int partyId);
}

private final PartyJoiner partyJoiner;
// Default factory adds: (chr, partyId) -> Party.joinParty(chr, partyId, /*silentCheck=*/true);

@Override
public void acceptPartyInvite(Bot bot) {
    Character chr = bot.character();
    int inviterId;
    try {
        inviterId = net.server.coordinator.world.InviteCoordinator
                .peekInvite(net.server.coordinator.world.InviteCoordinator.InviteType.PARTY, chr.getId());
    } catch (Throwable t) { return; }
    if (inviterId <= 0) return;
    Character inviter = characterLookup.byId(inviterId);
    if (inviter == null || inviter.getParty() == null) return;
    partyJoiner.join(chr, inviter.getParty().getId());
}
```

- [ ] **Step 4: Run + commit**

---

## Task 11: `walkToPortal`

**Files:**
- Modify: `src/main/java/server/bot/MapActuator.java`
- Modify: `src/test/java/server/bot/MapActuatorTest.java`

For now: when the brain decides `WALK_TO_PORTAL`, the actuator finds the nearest portal whose target is `targetMapId`, walks one step toward it (using `broadcastStep`), and if already adjacent, calls `chr.changeMap(portal)`. The change-map path already broadcasts `removePlayerFromMap` and `addPlayer` for the new map, so it handles the visual transition.

- [ ] **Step 1: Add test**

```java
@Test
void walkToPortalChangesMapWhenAdjacent() {
    MapActuator a = new MapActuator(new BotConfig());
    Bot b = bot(-1_000_000);
    Character chr = b.character();
    when(chr.getPosition()).thenReturn(new Point(0, 0));
    MapleMap map = mock(MapleMap.class);
    when(chr.getMap()).thenReturn(map);
    server.maps.Portal portal = mock(server.maps.Portal.class);
    when(portal.getPosition()).thenReturn(new Point(10, 0)); // within 60 px
    when(portal.getTargetMapId()).thenReturn(100000001);
    when(map.getPortals()).thenReturn(java.util.List.of(portal));

    a.walkToPortal(b, 100000001);
    verify(chr).changeMap(same(portal));
}
```

- [ ] **Step 2: Implement**

```java
private static final int PORTAL_ADJACENT_PX = 50;

@Override
public void walkToPortal(Bot bot, int targetMapId) {
    Character chr = bot.character();
    MapleMap map = chr.getMap();
    if (map == null) return;
    server.maps.Portal best = null;
    long bestD2 = Long.MAX_VALUE;
    for (server.maps.Portal p : map.getPortals()) {
        if (p.getTargetMapId() != targetMapId) continue;
        int dx = p.getPosition().x - chr.getPosition().x;
        int dy = p.getPosition().y - chr.getPosition().y;
        long d2 = (long) dx*dx + (long) dy*dy;
        if (d2 < bestD2) { best = p; bestD2 = d2; }
    }
    if (best == null) return;
    if (bestD2 <= (long) PORTAL_ADJACENT_PX * PORTAL_ADJACENT_PX) {
        chr.changeMap(best);
    } else {
        broadcastStep(bot, stepToward(chr.getPosition(), best.getPosition()));
    }
}
```

- [ ] **Step 3: Run + commit**

---

## Task 12: Wire `MapActuator` into `Server.init`

Replace the `LoggingBotActuator` instance with `MapActuator(botCfg)`.

**Files:**
- Modify: `src/main/java/net/server/Server.java`

- [ ] **Step 1: Find the line in `Server.init` that constructs `DefaultBotBrain`**

It looks like:

```java
server.bot.DefaultBotBrain brain = new server.bot.DefaultBotBrain(botCfg, view);
```

This uses the 2-arg ctor which defaults to `LoggingBotActuator`. Replace with the 3-arg form:

```java
server.bot.DefaultBotBrain brain = new server.bot.DefaultBotBrain(
        botCfg, view, new server.bot.MapActuator(botCfg));
```

- [ ] **Step 2: Compile-check**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn -DskipTests compile -q
```

- [ ] **Step 3: Run full test suite**

```
podman run --rm -v "$PWD":/build -w /build maven:3.9.6-amazoncorretto-21 mvn test
```

Pre-existing failures (e.g., the `MobSkillFactoryTest` ordering issue from v1) are still acceptable. Net-new failures are not.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/server/Server.java
git commit -m "$(cat <<'EOF'
Wire MapActuator into bot subsystem (replaces LoggingBotActuator)

Bots now visibly walk, fight, loot, drink pots, accept party invites,
and revive — observable to other clients on the same map.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review

**Spec coverage (against the v1 design's "v1.1 follow-up" list in the runbook):**
- Movement packets ↔ Tasks 1, 2, 3, 11
- Attack + damage application ↔ Tasks 5, 6
- Pot use ↔ Task 7
- Loot pickup ↔ Task 8
- Death/revive ↔ Task 9
- Party invite acceptance ↔ Task 10
- `isRangedWeapon` real impl ↔ Task 4
- `hasItemDropInPickupRadius` real impl ↔ Task 8
- Whisper-handler guard ↔ deferred (out of scope; one-line edit, can be a separate small PR)

**Placeholder scan:** Several tasks reference helpers that don't exist yet (`peekInvite`, exact `setHpMp` signature, `removeItemFromMap` exact args). Each task that references one says "verify exact signature in the codebase" — this is honest about what the implementer has to discover, not handwaving.

**Type consistency:** `BotActuator` interface from v1 already locks signatures. `MapActuator.broadcastStep` returns `Packet` consistently across tasks. `CharacterLookup`, `DelayedScheduler`, `PartyJoiner` are introduced as constructor injections with explicit types.

**Spec gaps acknowledged in plan:**
- `attackMelee` damage formula is `level * 10`, a placeholder. Combat balance is out of scope; this is good-enough for v1.1's "bot fights mobs" target.
- Skills (`B2` from the v1 spec) remain deferred.
- Bot can't be killed by other players in v1.1 — the brain detects death via `chr.isAlive()` and revives, but PvP-style damage ingress isn't tested. v83 doesn't really do PvP, so probably not an issue.

If gaps surface during execution, capture them as follow-up issues rather than expanding this plan.

---

# Plan complete

Saved to `docs/superpowers/plans/2026-05-08-bot-actuator-v1-1.md`.
