# Player Bot v1 — Manual Smoke Runbook

The v1 implementation lands the full decision surface (brain, scheduler, registry, commands, MCP tools) but ships with a `LoggingBotActuator` stub for action execution — actions are decided correctly but only logged, not yet emitted as packets to the game client. This runbook describes what's verifiable today and what waits on a follow-up `MapActuator` impl.

## Prereqs

- `bots.enabled: true` in `config.yaml`
- One client logged in to a GM character on world 0, channel 0
- A second client logged in as a regular player in the same channel
- Server logs visible (the stub actuator emits `DEBUG` lines)

## Verifiable today (v1)

1. GM runs `@bot spawn`. Confirm message "spawned BotNN" appears.
2. Second client confirms BotNN is visible on the map (this exercises `MapleMap.addPlayer` via `MapPlacer.placeOnMap`).
3. Server log shows the brain ticking `BotNN` every 200ms with action decisions — at IDLE no log; under `FOLLOW`, `STEP_TOWARD_TARGET` decisions every tick (via `LoggingBotActuator.stepTowardTarget`).
4. GM runs `@bot follow <regular player name>`. Bot's mode flips to `FOLLOW` and target is set.
5. GM runs `@bot grind` on a map with mobs. Brain logs `STEP_TOWARD_MOB` then `ATTACK_MELEE` (or `ATTACK_RANGED`) decisions per tick — but no actual damage is applied yet.
6. GM runs `@bot list` — the active bot appears with its mode.
7. GM runs `@bot stop` — bot's mode flips to `IDLE`, decisions stop logging.
8. GM runs `@bot despawn BotNN`. Bot is removed from `BotManager`; `MapPlacer.removeFromMap` calls `MapleMap.removePlayer` and the second client sees the bot disappear.

## MCP smoke (if `mcp.admin_enabled: true`)

```
cosmic.bot.spawn  {world:0, channel:0, map:100000000, x:0, y:0}
cosmic.bot.list   {world:0}
cosmic.bot.drive  {bot_id:<id>, mode:"GRIND"}
cosmic.bot.list   {}
```

Verify:
- Each call returns expected JSON.
- One row each in `mcp_admin_audit` for the `spawn` and `drive` calls.
- `list` reflects the state changes from `drive`.

## Known v1 limitations (deferred to v1.1)

These are decided correctly by the brain but not yet acted on by the actuator:

- Movement packets — `STEP_TOWARD_TARGET` / `STEP_TOWARD_MOB` are not broadcast to other clients, so other players see the bot frozen at its spawn point.
- Attack packets and damage application — `ATTACK_MELEE` / `ATTACK_RANGED` decisions are logged but no `damageMonster` call is made.
- Pot use — `USE_HP_POT` / `USE_MP_POT` log but don't consume inventory or heal.
- Loot pickup — `PICKUP` logs but `MapItem` stays on the ground.
- Death and revive — the brain detects `WAIT_REVIVE` correctly but doesn't actually re-broadcast spawn after the delay.
- Party invite acceptance — the brain detects pending invites via `InviteCoordinator` and decides `ACCEPT_PARTY_INVITE`, but the actual `Party.joinParty(...)` call is not yet made.
- Whisper filtering — the early-return guard at `WhisperHandler.java:53` documented in investigation notes section D was NOT applied. Players whispering a bot will see the standard "user is offline" reply.
- Ranged-weapon detection in `ServerWorldView.isRangedWeapon` returns `false` — all attacks dispatch as melee.
- Loot detection in `ServerWorldView.hasItemDropInPickupRadius` returns `false` — bots never see drops.

## Implementing v1.1

Replace the constructor wiring in `net.server.Server` to use a real `MapActuator implements BotActuator` instead of the default `LoggingBotActuator`. The actuator's methods translate `BotAction`s into the existing server methods documented in investigation notes sections D, E, F:

- `attackMelee` / `attackRanged` → `MapleMap.broadcastMessage(chr, PacketCreator.closeRangeAttack(...) | rangedAttack(...))` then `MapleMap.damageMonster(chr, monster, damage)`.
- `stepTowardTarget` / `stepTowardMob` → `MapleMap.broadcastMessage(chr, PacketCreator.movePlayer(...))` after updating `chr.getPosition()`.
- `useHpPot` / `useMpPot` → consume from `chr.getInventory(USE)` and apply heal stat.
- `acceptPartyInvite` → look up the pending invite in `InviteCoordinator` and call the same code path the client-side accept packet would invoke.
- `walkToPortal` → `Character.changeMap(portal)` after walking adjacent to the portal.
- `pickup` → `MapleMap.pickItemDrop(...)` with the bot's character as picker.
- `scheduleRevive` → `TimerManager.getInstance().schedule(...)` to reset HP/MP and re-broadcast spawn.

Also fill in the two `// TODO follow-up` stubs in `ServerWorldView` (`isRangedWeapon` via slot -11 + `WeaponType`, `hasItemDropInPickupRadius` by walking `MapleMap.getMapObjects()` for `MapItem` objects).

The `WhisperHandler` guard is a one-line edit that's safe to land at any point; not part of v1 because it touches an existing file outside the bot packages.
