# squaremap-pro — project rules

A map system for squaremap's tiles: a web editor where permitted players mark **buildings, roads,
railways and stations**, plus Google-Maps-style **navigation** over that drawn network.
Server-side Fabric mod + statically exported Next.js frontend served by the mod.
Task list and status: see `PLAN.md`.

**Command surface: exactly one command, `/mapedit`** (gives a one-time editor login link).
Viewing, editing and navigation all happen on the web. No other commands.

> Modules are being renamed in PLAN task 1 (`nav-core`→`map-core`, `nav-fabric`→`map-fabric`). Until
> that lands, read the old names as the new ones.

## Modules

| Module       | What                                                                        | May depend on                          |
|--------------|-----------------------------------------------------------------------------|----------------------------------------|
| `map-core`   | Plain Java. Feature model + validation, SQLite `FeatureStore`, road/rail network, router. | JDK 21, sqlite-jdbc, JUnit 5 (test)    |
| `map-fabric` | The mod: `/mapedit`, auth tokens/sessions, Javalin HTTP (API + static site + tiles), squaremap layer mirror | `map-core`, Minecraft, Fabric API, fabric-permissions-api, squaremap-api (compileOnly), Javalin |
| `web`        | Next.js (static export) + Leaflet: viewer, editor, navigation panel         | next, react, leaflet @geoman-io/leaflet-geoman-free (approved) |

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
| Loom               | **1.13** line (`fabric-loom` 1.13.6)       | build-logic                              |
| squaremap-api      | **`xyz.jpenilla:squaremap-api:1.3.12`**, `compileOnly` | `api/` diff v1.3.12..master is empty |
| squaremap (runtime)| squaremap-fabric **1.3.12** for MC 1.21.11 | tag `v1.3.12`                            |
| fabric-permissions-api | version for 1.21.11 — **verify from its repo/maven before use**, never guess | approved 2026-09-15 |

Reading squaremap source: `api/` on master == v1.3.12. For anything in `fabric/` or `common/`, read
the tag, not master: `git -C ../squaremap show v1.3.12:<path>`. Never recall squaremap, Fabric, or
Minecraft APIs from memory. Minecraft classes are not in the clone — read them from Loom's
generated sources (`./gradlew genSources`) and quote the signature used.

## Mappings: **Mojang official (Mojmap)** — never Yarn

`mappings(loom.officialMojangMappings())` — identical to squaremap v1.3.12's fabric build.
Names are `ServerLevel`, `ServerPlayer`, `CommandSourceStack`, `ClickEvent`.
Any Yarn name (`ServerWorld`, `ServerPlayerEntity`, `ServerCommandSource`, `class_1234`, `method_…`) in
code, docs, or reports is a defect.

## map-core purity rule

- `map-core` has **zero** imports from `net.minecraft`, `com.mojang`, `net.fabricmc`, `xyz.jpenilla`,
  `me.lucko`, `io.javalin`. Checked after every task:
  `grep -rnE "net\.minecraft|com\.mojang|net\.fabricmc|jpenilla|me\.lucko|javalin" map-core/` → must be empty.
- `map-core` is a plain `java-library`, built and tested with `./gradlew :map-core:test`, no game required.
- Model types are immutable; the router is a pure function over an immutable network snapshot. No static
  mutable state, no threads. I/O only inside `FeatureStore`.
- Subagents working in `map-core` must not touch `map-fabric`, and vice versa.

## Async / threading rule

1. **Server thread only**: `MinecraftServer`, players, command execution and feedback, synchronous
   permission checks on online players.
2. **Workers**: `FeatureStore` (SQLite) reads/writes, network rebuilds and routing run on a mod-owned bounded
   executor — never on the server thread.
3. **HTTP**: Javalin handlers never touch the server or level. They submit to the executor and complete
   asynchronously (`ctx.future`) with a timeout. A permission re-check that needs the server goes through
   `server.execute(...)` or the async offline-permission API.
4. **Never block the server thread** on a future (`join()`, `get()`, latches, sleeps).
5. **squaremap markers** may be mutated from worker threads (`SimpleLayerProvider` uses a
   `ConcurrentHashMap`, api v1.3.12). Layer *registration* happens on the server thread.
6. Every class used from more than one side carries a `// THREADING: ...` comment.

## Auth

- `/mapedit` (permission `squaremappro.edit`, fallback op level 2) → single-use token (256-bit, 5 min TTL)
  → clickable link `<publicUrl>/api/auth/redeem?token=…` → sets an `HttpOnly; SameSite=Lax` session cookie
  (UUID, name, expiry) and redirects to `/?edit=1`.
- Every write requires a valid session **and** header `X-Requested-With: squaremap-pro`, and re-checks the
  permission (a revoked player loses edit rights).
- Tokens and session ids are never logged.

## HTTP API (all JSON; draft — **pending user approval**, PLAN Q1)

All coordinates are **integer world block `x`,`z`** (features are 2D; no `y`). Clients draw vertices at
block centre (`x + 0.5`, `z + 0.5`) and convert to Leaflet with squaremap's CRS.
`world` is squaremap `WorldIdentifier.asString()` form, e.g. `minecraft:overworld`.

### Auth
- `GET /api/auth/redeem?token=` → 302 to `/?edit=1` with cookie, or 302 to `/?authError=expired`.
- `GET /api/auth/me` → `{ "loggedIn": true, "uuid": "…", "name": "Steve", "canEdit": true }` or `{ "loggedIn": false }`.
- `POST /api/auth/logout` → 204.

### Features v1
- `GET /api/worlds` → `[{ "id": "minecraft:overworld", "name": "world" }]`
- `GET /api/worlds/{world}/features` → `{ "schemaVersion": 1, "features": [Feature…] }` (public)
- `POST /api/worlds/{world}/features` body `FeatureInput` → 201 `Feature`
- `PUT /api/worlds/{world}/features/{id}` body `FeatureInput` + `"revision"` → 200 `Feature` · 409 on stale revision
- `DELETE /api/worlds/{world}/features/{id}?revision=` → 204 · 409 stale · 422 railway still has stations

```json
{
  "id": "f_7Kq2…", "type": "road", "revision": 3,
  "name": "Main Street",
  "geometry": [ { "x": 12, "z": -40 }, { "x": 150, "z": 10 } ],
  "props": { "roadClass": "street" },
  "createdBy": { "uuid": "…", "name": "Steve" }, "createdAt": "2026-09-15T10:00:00Z",
  "updatedBy": { "uuid": "…", "name": "Alex" },  "updatedAt": "2026-09-15T11:00:00Z"
}
```

| `type`     | `geometry`                          | `props`                                                          |
|------------|-------------------------------------|------------------------------------------------------------------|
| `building` | closed ring, ≥3 vertices, first ≠ last | `category`: `residential·commercial·public·industrial·other`, `description` |
| `road`     | polyline, ≥2 vertices               | `roadClass`: `highway·main·street·path`                           |
| `railway`  | polyline, ≥2 vertices               | `colour`: `#rrggbb`                                               |
| `station`  | exactly 1 vertex, on a vertex of `railwayId` | `railwayId`                                              |

Errors: `{ "error": "validation", "details": [ { "field": "geometry", "message": "…" } ] }` with 400/401/403/404/409/422.

### Route schema v2
`GET /api/route?world=<id>&from=<x>,<z>&to=<x>,<z>[&modes=walk,road,rail]` (public)

```json
{
  "schemaVersion": 2,
  "status": "ok",
  "world": "minecraft:overworld",
  "from": { "x": 12, "z": -40 },
  "to":   { "x": 310, "z": 95 },
  "legs": [
    { "mode": "walk", "name": null,          "points": [ {"x":12,"z":-40}, {"x":20,"z":-38} ], "distance": 8.2,  "duration": 1.9 },
    { "mode": "road", "name": "Main Street", "points": [ {"x":20,"z":-38}, {"x":150,"z":10} ], "distance": 138.6, "duration": 24.8 },
    { "mode": "rail", "name": "Red Line",    "points": [ … ], "fromStation": "Central", "toStation": "Harbour", "distance": 170.0, "duration": 21.3 }
  ],
  "distance": 316.8,
  "duration": 48.0,
  "error": null
}
```

| Field      | Notes                                                                                   |
|------------|-----------------------------------------------------------------------------------------|
| `status`   | `ok` · `no_path` · `invalid_request` · `world_not_found`                                 |
| `legs`     | consecutive; each leg's first point = previous leg's last point. `[]` unless `ok`. Consecutive road legs with the same name are merged. |
| `distance` | blocks (2D). `duration` seconds from configured speeds. `0` unless `ok`.                 |
| HTTP       | `200` ok/no_path · `400` invalid_request · `404` world_not_found                         |

Turn-by-turn instructions are derived client-side from leg `points`.

## Workflow

- Build: `./gradlew build`. Core tests: `./gradlew :map-core:test`. Web: `cd web && npm test && npm run build`.
- One task at a time from `PLAN.md`; a task is done only when its acceptance command is green when
  run by the lead, the purity grep is empty, and the threading rule holds.
