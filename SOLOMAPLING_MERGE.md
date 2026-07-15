# SoloMapling merge notes

SoloMapling ([MadaraGameDev/SoloMapling](https://github.com/MadaraGameDev/SoloMapling)) was merged into this Cosmic fork.

## What landed

- Full `soloMapling/` package (bots, FM, casino, movement, OPQ, etc.)
- Headless `client.BotClient` + GM commands: `!bot`, `!move`, `!env`, `!opq`, `!reactor`, `!betafmshop`, `!gcmove`, `!test`, `!fmbot`, `!tradebot`
- Config flag `server.SPAWN_BOTS_ON_STARTUP` (default `true` in `config.yaml`)
- Liquibase data: `162-fmbot-data.sql`, `171-casino-shop-data.sql`
- Maven deps: JGraphT + JGraphX

## What was removed

The previous TankesStory player-bot stack was discarded (as requested):

- `client.bot.*`, `server.bot.*`, `config.BotConfig`
- `BotCommand` (gm0)
- MCP tools: `BotListTool`, `BotSpawnTool`, `BotDriveTool`

## Preserved TankesStory features

- MCP server + admin/SQL tools
- IRC `@world` bridge
- Custom shops: `addtoshop` / `removefromshop` / `sell`
- Multi-world rates, MapFactory Bera `originalId` behavior, etc.

## Run

```bash
# Docker
docker compose up --build

# Or fat jar
mvn -DskipTests package
java -jar target/Cosmic.jar   # after arranging wz/, scripts/, config.yaml
```

Set `HOST` / `LANHOST` in `config.yaml` to your public IP. First real player after cold boot may need a relog so bots share a client address (improved when `SPAWN_BOTS_ON_STARTUP: true`).
