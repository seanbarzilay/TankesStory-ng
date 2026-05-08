# Player Bot — Construction Surface Notes

Spike output for the player-bot v1 plan. All references are
`Class.method:line` against the master tree at the time of writing.

## A. Client subclassing

- `Client` constructor signature:
  `public Client(Type type, long sessionId, String remoteAddress, PacketProcessor packetProcessor, int world, int channel)`
  (Client.java:158). Stores `type`, `sessionId`, `packetProcessor` as `final`; the rest are mutable. Body never dereferences `packetProcessor`, so passing `null` is safe at construction time — `packetProcessor` is only consumed in `channelRead` (Client.java:212), which the bot never enters.
- `ioChannel` is a `private io.netty.channel.Channel` field at Client.java:118, assigned only inside `channelActive` (Client.java:190). For a bot that never runs through Netty's `channelActive`, the field stays `null`.
- Methods that touch `ioChannel` (must be no-ops or guarded in `BotClient`):
  - `closeSession` — Client.java:284-286 (calls `ioChannel.close()`).
  - `disconnectSession` — Client.java:288-290 (calls `ioChannel.disconnect()`).
  - `checkIfIdle` — Client.java:1165-1181 (reads `ioChannel.isActive()` from a scheduled task).
  - `sendPacket` — Client.java:1466-1473 (calls `ioChannel.writeAndFlush(packet)`).
  Note: `channelActive`/`channelInactive`/`channelRead`/`exceptionCaught` (Client.java:182, 254, 205, 241) also reference Netty state via `ChannelHandlerContext` but are Netty callbacks the bot never receives, so they don't need to be overridden.
- Methods/fields whose visibility must be relaxed from `private` to `protected` so `BotClient` can override or read them: **none required for the four touch points above** — they are already `public` (`closeSession`, `disconnectSession`, `sendPacket`) or `public` (`checkIfIdle`). `BotClient` simply overrides each as a no-op (or, for `sendPacket`, routes the packet to the bot's own consumer / drops it). The `ioChannel` field itself stays `private`; no `BotClient` code needs to read it because every read site lives in a method we override.
- Constructor recommended for `BotClient`: `BotClient(int world, int channel)` — call
  `super(Type.CHANNEL, /*sessionId=*/-1L, /*remoteAddress=*/"bot", /*packetProcessor=*/null, world, channel)`.
  `PacketProcessor` is referenced only in `channelRead` (Client.java:212), which is unreachable for a bot that has no live `ChannelHandlerContext`. The constructor stores it as a final field but does not call any methods on it, so `null` is safe.
  Caveat: `Client.createMock()` (Client.java:177) already uses
  `new Client(null, -1, null, null, -123, -123)` for tests, confirming `null` for
  `packetProcessor`/`type`/`remoteAddress` survives construction.
- Additional override-or-no-op surface (defence-in-depth, all `public` in `Client`, no visibility change required):
  - `disconnect(boolean, boolean)` — Client.java declares this around the `removeIncomingInvites` call sites at Client.java:928 and Client.java:1510. Override to a no-op so DC paths invoked by maps (e.g. via map scripts) cannot tear down the bot through Netty.
  - `announceServerMessage` — Client.java:1441 (calls `sendPacket`; once `sendPacket` is no-op'd, this is safe).

## B. Character construction without DB

- `Character` is constructed via the existing public static factory
  `Character.getDefault(Client c)` at Character.java:456-508. The private
  no-arg constructor `Character()` at Character.java:359-420 initialises
  inventories, key bindings, position `(0,0)`, and a stat-listener; the
  factory then fills in level=1 / Job.BEGINNER / 50 HP / 5 MP / accountid /
  buddylist / inventory slot limits / trockmaps / VIP trockmaps. This is the
  same factory that `client.creator.CharacterFactory.createNewCharacter`
  (CharacterFactory.java:50) uses before persisting the character.
- The `generateCharacterEntry` path at Character.java:6786 produces a *copy*
  for character-list rendering — it requires an already-initialised source
  Character, so it is **not** the bot construction path.
- The minimum field set needed for `MapleMap.addPlayer(chr)` at
  MapleMap.java:2298-2529 to succeed without NPE:
  - `chr.getClient()` — read at MapleMap.java:2331, 2339, 2395, 2452, 2494, 2528. Must return a non-null `Client` (the `BotClient` instance). `getDefault(Client)` already wires `ret.client = c` (Character.java:458).
  - `chr.getParty()` — read at MapleMap.java:2300. Returns `null` for a fresh `Character` (`party` field default is `null`); the null branch is taken safely.
  - `chr.getId()` — read throughout (e.g. MapleMap.java:2306, 2447, 2638). Must be set to a unique synthetic ID (negative range; see below).
  - `chr.getMapId()` / `chr.setMapId()` — `setMapId` is called by `addPlayer` at MapleMap.java:2314 *via* `chr.setMapId(mapid)`. The bot must have entered the map prior (caller sets `chr.setMap(map)`). `getDefault` leaves `mapid` at the default (0) but `setMap(map)` and the immediate `setMapId(mapid)` inside `addPlayer` cover this.
  - `chr.getBuffedValue(BuffStat.MONSTER_RIDING)` — MapleMap.java:2341. `getDefault` initialises `effects` as empty `EnumMap` so this returns `null` — safe.
  - `chr.getPets()` — MapleMap.java:2404. Field `pets` is `final Pet[3]` with all `null` (Character.java:266). Loop breaks immediately.
  - `chr.getMonsterCarnival()` — MapleMap.java:2415. Default `null`.
  - `chr.getChalkboard()` — MapleMap.java:2434. Default `null`.
  - `chr.isHidden()` — MapleMap.java:2442. Default `false`.
  - `chr.getObjectId()` — MapleMap.java:2466. Overridden by `Character` at Character.java:9672-9675 to return `getId()`.
  - `chr.getPlayerShop()` — MapleMap.java:2471. Default `null`.
  - `chr.getDragon()` — MapleMap.java:2475. Default `null`.
  - `chr.getStatForBuff(BuffStat.SUMMON)` — MapleMap.java:2486. Empty `effects` → null.
  - `chr.getEventInstance()` — MapleMap.java:2500. Default `null`.
  - `chr.getFitness()`, `chr.getOla()` — null on fresh char.
  - `chr.getPosition()` — MapleMap.java:2407 (and inside `broadcastSpawnPlayerMapObjectMessage` at MapleMap.java:2806+). `getDefault` constructor sets `Point(0,0)` (Character.java:419); caller should `setPosition(spawnPoint.getPosition())` to a real portal before adding.
  - `chr.getName()` — read by `PacketCreator.spawnPlayerMapObject` at PacketCreator.java:1946. Caller must `setName("...")`.
  - `chr.getGuildId()` — `PacketCreator.spawnPlayerMapObject` at PacketCreator.java:1947. Default `0`, takes the `< 1` branch (no guild lookup).
  - `chr.getInventory(InventoryType.EQUIPPED)` — `getDefault` initialises all inventories at Character.java:411, so this is non-null even when empty. Equipment slots populated via `inv.addItemFromDB(...)` per `CharacterFactory.createNewCharacter`.
- The chosen path for the bot: **(b) introduce a new package-private factory `Character.createBot(BotClient, BotPreset)` co-located with `getDefault` in Character.java**. Reason: `Character.id` is a package-private instance field (Character.java:204) with **no public setter** — any code outside the `client` package cannot assign an ID. The bot needs a synthetic negative ID and the freedom to set non-default `level`, `job`, `mapid`, `name`, equipment, and HP/MP without going through `insertNewChar` / DB persistence. A new factory `static Character createBot(BotClient client, BotPreset preset)` placed alongside `getDefault` at ~Character.java:456 can call `getDefault(client)` for the base init, then directly assign `ret.id`, `ret.name`, `ret.level`, `ret.job`, `ret.mapid`, `ret.maxhp`, `ret.maxmp`, equipment items via `ret.getInventory(EQUIPPED).addItemFromDB(...)`, and call `ret.setPosition(...)` / `ret.setMap(map)`. Reusing `getDefault` directly is insufficient because of `id`. Reusing `loadCharFromDB` (Character.java:6846) is wrong — it reads from `characters` table.
- Synthetic ID range collision check: at boot, query
  `SELECT MIN(id) FROM characters` via `tools.DatabaseConnection.getConnection()` (DatabaseConnection.java:30) — if it returns a value `<= -1_000_000`, abort startup. The DB connection helper used by similar startup checks is `tools.DatabaseConnection.getConnection()` (try-with-resources `Connection`, throws `SQLException`). Existing usage pattern visible at Client.java:1221, Server.java:1033-1036.
  Note: no existing code probes `MIN(id)` of characters; this is a new sanity check the bot factory's static initialiser introduces.

## C. Party invite

- Party invite handler class/method: `PartyOperationHandler.handlePacket` at PartyOperationHandler.java:44, with the *invite* sub-case at PartyOperationHandler.java:76-113 (case `4`). The acceptance sub-case is at PartyOperationHandler.java:64-75 (case `3`).
- Server-side state for pending invites: `net.server.coordinator.world.InviteCoordinator` (InviteCoordinator.java:34). State is real and queryable:
  - `InviteCoordinator.createInvite(InviteType.PARTY, fromChar, partyId, targetCid)` — InviteCoordinator.java:98, called at PartyOperationHandler.java:98.
  - `InviteCoordinator.hasInvite(InviteType.PARTY, targetCid)` — InviteCoordinator.java:102. **The recipient's pending invites are server-side queryable.**
  - `InviteCoordinator.answerInvite(InviteType.PARTY, targetCid, partyId, accept)` — InviteCoordinator.java:106. Called from `PartyOperationHandler` case 3 at PartyOperationHandler.java:67 and from `DenyPartyRequestHandler.handlePacket` at DenyPartyRequestHandler.java:44. The reference value is `partyId` (an `Integer`).
  - On accept the handler calls `Party.joinParty(player, partyid, false)` (Party.java:354) which mutates world state and broadcasts party packets — bot can call this directly since it's `public static`.
  - Backing storage: `ConcurrentHashMap<Integer, Object>` per `InviteType` (InviteCoordinator.java:52-61). `inviteFrom` holds the inviter `Character`, `inviteParams` holds the param array, `inviteTimeouts` is a tick counter, `invites` holds the reference value (`partyId` here).
  - The `partyId` reference is recoverable via the `inviteFrom`/`inviteParams` maps since `inviteFrom.get(targetCid)` returns the inviter `Character`, whose `getPartyId()` gives the party id (the invitation's reference value). However those maps are *package-private*, so cross-package code must use `InviteCoordinator.hasInvite(...)` and obtain `partyId` from the bot's known inviter character.
- Decision: **poll `InviteCoordinator.hasInvite(InviteType.PARTY, botCid)` from the bot's tick loop and call `Party.joinParty(bot, partyId, false)` followed by `InviteCoordinator.answerInvite(PARTY, botCid, partyId, true)` on hit.** Reason: the existing `InviteCoordinator` *is* a server-side queryable state — the spec's "if pending invites are purely a packet to the recipient" branch does not apply. Polling is strictly less invasive than adding a new `PartyInviteListener` interface and a new call into `PartyOperationHandler`; it requires zero edits to existing handlers. The bot's tick loop already needs a heartbeat (per F), so adding an invite-poll step is free.
  Resolving the `partyId` for the answer: the bot stores its inviter's character object on the `BotInvitee` side at the moment a player whisper triggers an invite request, or — more robustly — peeks the inviter via a small `InviteCoordinator.peekInvite(type, targetCid)` accessor (single-line helper that reads `inviteFrom`/`inviteParams`). This is a small, additive change with no behavioural risk and lets the bot pull `partyId` from the existing structure rather than re-deriving it. The implementer should add this accessor in Task 14.

## D. Whisper

- Whisper handler class/method: `WhisperHandler.handlePacket` at WhisperHandler.java:48-73.
- "User is offline" branch: WhisperHandler.java:53-56 — if `getCharacterByName(name)` returns `null`, sends `PacketCreator.getWhisperResult(name, false)` to the sender. Because the bot is added to `PlayerStorage` for collision detection (broadcast/aggro/spawn), `getCharacterByName(botName)` will *not* return null and the whisper would otherwise fall through to `handleWhisper` at WhisperHandler.java:90, which calls `target.sendPacket(...)` (line 105) — that goes through `BotClient.sendPacket` (a no-op) and effectively drops silently, but the sender still gets a fake "delivered" reply at line 108.
- Decision for filtering whispers to bots: **add an early guard at WhisperHandler.java:53, before the existing null check:**
  ```java
  if (BotManager.isBot(name)) {
      c.sendPacket(PacketCreator.getWhisperResult(name, false));
      return;
  }
  ```
  This produces the exact same "user is offline" reply the existing offline branch produces, so the sender's UI behaves identically. `BotManager.isBot(String name)` is a new static lookup the implementer adds in `mcp.bot` (or wherever `BotManager` lives) — checks an in-memory `Set<String>` of bot names. This avoids invasive changes to `WhisperHandler` and avoids the silent-drop confusion described above.

## E. Attack dispatch

- Equipped weapon type lookup (one-liner that returns whether the bot's equipped weapon is ranged):
  ```java
  WeaponType wt = ItemInformationProvider.getInstance()
                  .getWeaponType(bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11).getItemId());
  boolean ranged = wt == WeaponType.BOW || wt == WeaponType.CROSSBOW
                || wt == WeaponType.CLAW || wt == WeaponType.GUN;
  ```
  Sources:
  - Slot `-11` is the weapon equip slot (Character.java:813, 7731).
  - `ItemInformationProvider.getWeaponType(int itemId)` at server/ItemInformationProvider.java:616 returns a `client.inventory.WeaponType` (WeaponType.java:24-55).
  - Existing ranged-classification pattern at Character.java:7735-7739:
    `boolean bow = weapon == WeaponType.BOW; boolean crossbow = weapon == WeaponType.CROSSBOW; boolean claw = weapon == WeaponType.CLAW; boolean gun = weapon == WeaponType.GUN;`
  - Null-handling: bot must guard against `getItem((short) -11) == null` (no weapon equipped) and treat as melee/no-attack.
- Existing damage-application call from server-side combat: `MapleMap.damageMonster` has two overloads:
  - `public boolean damageMonster(Character chr, Monster monster, int damage)` — MapleMap.java:1272-1274 (delegates to the second).
  - `public boolean damageMonster(final Character chr, final Monster monster, final int damage, short delay)` — MapleMap.java:1276-1304. Calls `monster.damage(chr, damage, false)`, handles self-destruct, and calls `killMonster(monster, chr, true, delay)` if killed. Returns `true` on hit (even if the monster was already dead it returns `false`; live + damaged returns `true`).
- `PacketCreator.closeRangeAttack(...)` signature (PacketCreator.java:2344-2351):
  `public static Packet closeRangeAttack(Character chr, int skill, int skilllevel, int stance, int numAttackedAndDamage, Map<Integer, AttackTarget> targets, int speed, int direction, int display)`.
  Body delegates to `addAttackBody` (PacketCreator.java:2375) which writes `chr.getId()`, the targets map, etc.
- `PacketCreator.rangedAttack(...)` signature (PacketCreator.java:2353-2361):
  `public static Packet rangedAttack(Character chr, int skill, int skilllevel, int stance, int numAttackedAndDamage, int projectile, Map<Integer, AttackTarget> targets, int speed, int direction, int display)`.
  Same `addAttackBody` plus a trailing `writeInt(0)`.
- Fields the bot needs to populate before broadcasting either packet:
  - `chr.getId()` — from the synthetic bot id.
  - `chr` must be in the map and on `MapleMap.characters` (already after `addPlayer`).
  - `numAttackedAndDamage` is the byte `(numAttacked << 4) | numDamage` per the standard v83 attack packet — implementer must compute from the chosen targets/hits.
  - `targets`: `Map<Integer, AttackTarget>` keyed by monster oid. `AttackTarget` is the existing record/class in `tools` used by combat handlers (e.g. `AbstractDealDamageHandler`). Each target must list `numDamage` damage values.
  - For ranged: `projectile` is the projectile item id (arrow, bullet, throwing star). For a bot with a bow this is an arrow item id from `InventoryType.USE`.
  - `skill = 0`, `skilllevel = 0`, `stance = 0`, `direction = 0`, `display = 0`, `speed` from the weapon's attack speed are reasonable v1 defaults.
- The bot dispatches by:
  1. Computing damage locally (or stub-fixed).
  2. Calling `map.damageMonster(bot, monster, damage)` for the actual HP mutation.
  3. Calling `map.broadcastMessage(bot, PacketCreator.closeRangeAttack(...) | rangedAttack(...), false, true)` (MapleMap.java:2690) so other players see the swing animation.

## F. TimerManager

- Repeating-task API in `server.TimerManager`:
  - `public ScheduledFuture<?> register(Runnable r, long repeatTime)` — TimerManager.java:97-99. Schedules at fixed rate, `delay = 0`, `repeatTime` in ms.
  - `public ScheduledFuture<?> register(Runnable r, long repeatTime, long delay)` — TimerManager.java:93-95. Same with explicit initial delay.
  - One-shot variant: `public ScheduledFuture<?> schedule(Runnable r, long delay)` — TimerManager.java:101-103.
  - Underlying executor: `ScheduledThreadPoolExecutor` with `setRemoveOnCancelPolicy(true)` (TimerManager.java:74), so cancelling a returned future also frees its slot.
- The returned `java.util.concurrent.ScheduledFuture<?>` is what `BotScheduler.stop()` cancels via `future.cancel(false)`. Pattern already used throughout `Character` (e.g. `ScheduledFuture<?> hpDecreaseTask` at Character.java:301).
- Singleton accessor: `TimerManager.getInstance()` (TimerManager.java:43-45).

## G. CommandsExecutor registration

- Exact insertion point: between CommandsExecutor.java:402 and CommandsExecutor.java:404, inside `private void registerLv1Commands()` (starts at CommandsExecutor.java:396). Add:
  ```java
  addCommand("bot", 1, BotCommand.class);
  ```
  immediately after the line `addCommand("goto", 1, GotoCommand.class);` (CommandsExecutor.java:402) and before `commandsNameDesc.add(levelCommandsCursor);` (CommandsExecutor.java:404).
- Surrounding pattern (CommandsExecutor.java:396-405):
  ```java
  private void registerLv1Commands() {
      levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

      addCommand("whatdropsfrom", 1, WhatDropsFromCommand.class);
      addCommand("whodrops", 1, WhoDropsCommand.class);
      addCommand("buffme", 1, BuffMeCommand.class);
      addCommand("goto", 1, GotoCommand.class);
      // ← add `addCommand("bot", 1, BotCommand.class);` here

      commandsNameDesc.add(levelCommandsCursor);
  }
  ```
- `addCommand(String, int, Class<? extends Command>)` is private (CommandsExecutor.java:329-346). It rejects duplicate names with a warn log, instantiates via no-arg constructor, calls `setRank(rank)`, and registers in `registeredCommands`. `BotCommand` therefore needs a public no-arg constructor and to extend whatever `Command` superclass other commands extend (look at `GotoCommand` or `WarpCommand` for the template — both in `client.command.commands.gm`).
- Required GM rank for the bot: `1` matches the spec ("level 1 GM"). Existing Lv1 commands include `goto`, `buffme`, `whodrops`. If the bot should be GM-only (rank ≥ 2), move it to `registerLv2Commands()` (CommandsExecutor.java:408) instead.

## H. MCP tool registration

- Site where existing tools (e.g. `MobWhereTool`) are constructed and added to the registry: `net.server.Server` `init()` (or equivalent boot method) at Server.java:992-1006 (the `mcpTools` `ArrayList` initial population) and Server.java:1007-1073 (conditional additions for sql/edit/admin sections). Final wiring at Server.java:1074: `mcpServer = new McpServer(mcpConfig, new ToolRegistry(mcpTools));`.
- The two new bot tools (`BotSpawnTool`, `BotDriveTool`) belong in the `mcpConfig.adminEnabled()` block at Server.java:1032-1073, alongside `RunCommandTool` (Server.java:1063), `AuditListTool` (Server.java:1064), and `DbExecuteTool` (Server.java:1070) — the other mutating tools.
- Constructor dependencies for the new tools:
  - Both tools need `mcp.admin.AuditLog adminAuditLog` (already constructed at Server.java:1039: `new mcp.admin.AuditLog(dbConn)`).
  - `BotSpawnTool` additionally needs the `BotManager` (or whatever name; lives in `mcp.bot` per the plan) and access to `Server.getInstance()` to resolve world/channel/map context. Existing pattern for crossing into game state: see how `RunCommandExecutor.CharacterResolver` is constructed at Server.java:1045-1056 (lambda over `getWorlds()` → `getPlayerStorage().getAllCharacters()`).
  - `BotDriveTool` needs the same `BotManager` plus access to `MapleMap` instances via the bot's stored `Character.getMap()`.
- The exact name of the audit method: there is **no `AuditLog.record(...)` method**. The actual API is:
  - `public long insert(AuditEntry entry) throws SQLException` — AuditLog.java:29-33. Allocates a `Connection` per call.
  - `public long insertInConnection(Connection c, AuditEntry entry) throws SQLException` — AuditLog.java:35-55. For when the caller is already inside a transaction.
  - `AuditEntry` is a record at AuditEntry.java:5-14:
    `record AuditEntry(String callerIp, String callerNote, String tool, JsonNode argsJson, String resultSummary, JsonNode beforeJson, String afterSummary, boolean ok)`.
  Existing usage pattern (RunCommandTool.java:60-68): build an `ObjectNode argsJson`, instantiate `new AuditEntry(null, callerNote, "cosmic.admin.run_command", argsJson, result.output(), null, null, result.ok())`, then `auditLog.insert(entry)`. The plan's references to `AuditLog.record(String tool, JsonNode before, JsonNode after)` should be updated to the actual `insert(AuditEntry)` API; `before`/`after` map to `beforeJson`/`afterSummary` on `AuditEntry`. For the bot tools, populate:
  - `tool`: `"cosmic.bot.spawn"` / `"cosmic.bot.drive"` (matching the `Tool.name()` they declare).
  - `argsJson`: the input args object.
  - `beforeJson`: pre-image (e.g. bot did not exist; null).
  - `afterSummary`: short description (e.g. `"spawned bot id=-1234567 name=Botty in map 100000000"`).
  - `ok`: result.

## Findings that affect downstream tasks

1. **Task 6 (BotFactory body)**: `Character.id` has no public setter, so `BotFactory` cannot live outside the `client` package. Either place `createBot(...)` as a package-private static on `Character` (recommended), or place the entire `BotFactory` class in package `client`. The plan should make this explicit.
2. **Task 14 (party hook)**: `InviteCoordinator` *is* server-side queryable, so the simpler poll-based design is viable and removes the need for a new `PartyInviteListener` interface and edits to `PartyOperationHandler`. Recommend adding only a small `peekInvite(InviteType, int)` accessor to `InviteCoordinator` to expose the inviter `Character` and `partyId`, since the underlying maps are package-private.
3. **Task 16 (audit method)**: The audit API is `AuditLog.insert(AuditEntry)`, not `AuditLog.record(...)`. `AuditEntry` is an 8-arg record; `before` and `after` correspond to `beforeJson` (`JsonNode`) and `afterSummary` (`String`), not symmetric `JsonNode` pairs. Update the plan to match.
4. **Task 19 (server boot wiring)**: New tool registration goes inside the `if (mcpConfig.adminEnabled()) { ... }` block at Server.java:1032-1073, between the `mcpTools.add(new mcp.tools.AuditListTool(dbConn));` line at Server.java:1064 and the `if (mcpConfig.dbExecuteEnabled() ...` block at Server.java:1066. The startup synthetic-id collision check (`SELECT MIN(id) FROM characters`) should run *before* `mcpTools.add(new mcp.tools.BotSpawnTool(...))` so a collision aborts startup before any bot tool is exposed.
