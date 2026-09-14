# squaremap-pro — project rules

Google-Maps-style navigation for squaremap's web map. Server-side Fabric mod + Next.js frontend.
Task list and status: see `PLAN.md`.

## Modules

| Module       | What                                                                 | May depend on                          |
|--------------|----------------------------------------------------------------------|----------------------------------------|
| `nav-core`   | Plain Java. All pathfinding, simplification, region graph, SQLite.   | JDK 21, sqlite-jdbc, JUnit 5 (test)    |
| `nav-fabric` | The mod: WorldView adapter, Brigadier commands, squaremap layer, HTTP | `nav-core`, Minecraft, Fabric, squaremap-api (compileOnly), Javalin |
| `web`        | Next.js + Leaflet, squaremap tiles, directions panel                  | Mock route endpoint only               |

Any new dependency not listed above → escalate to the user first.

## Versions (verified 2026-09-14 against `../squaremap`)

The clone's `master` targets **Minecraft 26.2**. We target **1.21.11**, so the reference revision is
tag **`v1.3.12`** (2025-12-11), the last squaremap release built for 1.21.11. Versions below come
from `gradle/libs.versions.toml` at commit `f3e6f72` (last 1.21.11 commit before the 26.1 update).

| Thing              | Version                                    | Source                                   |
|--------------------|--------------------------------------------|------------------------------------------|
| Java               | **21** (toolchain; PATH default is JDK 25 — do not rely on it) | squaremap `indra.javaVersions.target(21)` |
| Minecraft          | **1.21.11**                                | `minecraft = "1.21.11"`                  |
| Fabric Loader      | **0.18.4**                                 | `fabricLoader = "0.18.4"`                |
| Fabric API         | **0.141.3+1.21.11**                        | `fabricApi = "0.141.3+1.21.11"`          |
| Loom               | **1.13** line (squaremap used `quiet-fabric-loom 1.13-SNAPSHOT`; we use upstream `fabric-loom`) | build-logic |
| squaremap-api      | **`xyz.jpenilla:squaremap-api:1.3.12`**, `compileOnly` | `api/` diff v1.3.12..master is empty |
| squaremap (runtime)| squaremap-fabric **1.3.12** for MC 1.21.11 | tag `v1.3.12`                            |

Reading squaremap source: `api/` on master == v1.3.12. For anything in `fabric/` or `common/`, read
the tag, not master: `git -C ../squaremap show v1.3.12:<path>`. Never recall squaremap, Fabric, or
Minecraft APIs from memory. Minecraft classes are not in the clone — read them from Loom's
generated sources (`./gradlew genSources`) and quote the signature used.

## Mappings: **Mojang official (Mojmap)** — never Yarn

`mappings(loom.officialMojangMappings())` — identical to squaremap v1.3.12's fabric build.
Names are `ServerLevel`, `BlockState`, `PathComputationType`, `ChunkAccess`, `CommandSourceStack`.
Any Yarn name (`ServerWorld`, `NavigationType`, `ServerCommandSource`, `class_1234`, `method_…`) in
code, docs, or reports is a defect.

## nav-core purity rule

- `nav-core` has **zero** imports from `net.minecraft`, `com.mojang`, `net.fabricmc`, `xyz.jpenilla`.
  Checked after every task:
  `grep -rnE "net\.minecraft|com\.mojang|net\.fabricmc|jpenilla" nav-core/` → must be empty. Any hit = rejected.
- `nav-core` has no Minecraft/Fabric/squaremap/Loom dependency in its build file. It is a plain
  `java-library` built and tested with `./gradlew :nav-core:test`, no game required.
- Algorithms are pure functions over `WorldView`: no static mutable state, no threads, no I/O except
  the explicit SQLite serialization API (task 5).
- Subagents working in `nav-core` must not touch `nav-fabric`, and vice versa.

## Async / threading rule

1. **Server thread only**: every read or write of `ServerLevel`, `LevelChunk`, `BlockState` lookups
   via a level, `MinecraftServer`, players, and command source feedback. No exceptions.
2. **Snapshot boundary**: `nav-fabric` copies the needed chunk data into immutable snapshot objects
   *on the server thread* (bounded work per tick), then hands the snapshot to worker threads. The
   `WorldView` a worker sees reads only snapshots — never a live level. Every class that crosses this
   boundary carries a comment `// THREADING: ...` stating which side it runs on.
3. **Workers**: pathfinding, region-graph building, and SQLite I/O run on a mod-owned bounded
   executor — never on the server thread, never on Netty/Javalin request threads for long work.
4. **Never block the server thread** on a future (`join()`, `get()`, latches, sleeps). Results go back
   with `server.execute(...)`.
5. **squaremap markers** may be mutated from worker threads: `SimpleLayerProvider` stores markers in
   `private final Map<Key, Marker> markers = new ConcurrentHashMap<>();` (api, v1.3.12). Layer
   *registration* happens on the server thread.
6. **HTTP**: Javalin handlers do not touch the level; they submit to the executor and complete
   asynchronously with a timeout.

## Route JSON schema (v1)

`GET /route?from=<x>,<z>&to=<x>,<z>[&world=<namespace:value>]` — `world` defaults to
`minecraft:overworld`. Y is resolved server-side via `WorldView.groundY`.

All coordinates are **integer world block coordinates**; `y` is the feet block the player stands in.
Clients draw at block centre (`x + 0.5`, `z + 0.5`). The web converts to Leaflet using squaremap's CRS.

```json
{
  "schemaVersion": 1,
  "status": "ok",
  "world": "minecraft:overworld",
  "from":  { "x": 12, "y": 64, "z": -40 },
  "to":    { "x": 310, "y": 71, "z": 95 },
  "points": [ { "x": 12, "y": 64, "z": -40 }, { "x": 150, "y": 66, "z": 10 }, { "x": 310, "y": 71, "z": 95 } ],
  "distance": 342.7,
  "nodesExpanded": 18422,
  "error": null
}
```

| Field           | Type                 | Notes                                                                  |
|-----------------|----------------------|------------------------------------------------------------------------|
| `schemaVersion` | int                  | Always `1`. Any change to this schema needs user approval.             |
| `status`        | string enum          | `ok` · `no_path` · `cap_exceeded` · `invalid_request` · `world_not_found` · `not_ready` |
| `world`         | string               | squaremap `WorldIdentifier.asString()` form                            |
| `from`, `to`    | `{x,y,z}` or `null`  | Requested endpoints after Y resolution; `null` if unresolvable         |
| `points`        | `{x,y,z}[]`          | Simplified polyline, first = `from`, last = `to`. `[]` unless `ok`.    |
| `distance`      | number               | Blocks, 3D length along `points`. `0` unless `ok`.                     |
| `nodesExpanded` | int                  | Search effort (fine + coarse). `0` if no search ran.                   |
| `error`         | string or `null`     | Human-readable detail; `null` when `ok`.                               |

HTTP status: `200` for `ok`, `no_path`, `cap_exceeded` (valid answers); `400` `invalid_request`;
`404` `world_not_found`; `503` `not_ready` (graph not built / world not loaded).
Turn-by-turn instructions are derived client-side from `points` (not in the payload).

## Workflow

- Build: `./gradlew build`. Core tests: `./gradlew :nav-core:test`. Web: `cd web && npm test`.
- One task at a time from `PLAN.md`; a task is done only when its acceptance command is green when
  run by the lead, the purity grep is empty, and the threading rule holds.
