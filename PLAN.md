# PLAN

Status markers: `[ ]` todo · `[~]` in progress · `[x]` done (lead-verified) · `[!]` blocked / escalated

Rules for every task: see `CLAUDE.md` (versions, Mojmap, core purity, threading, API schemas).
Lead verification after each task: run the acceptance command myself, purity grep on `map-core`,
check the threading rule, update this file, stop and report.

## Product (re-scoped 2026-09-15)

1. **Map editor (main product).** A web map over squaremap's tiles where players with permission add,
   edit and delete **buildings** (polygons), **roads** (polylines), **railways** (polylines) and
   **stations** (points on a railway). Everyone can view it.
2. **Editor access.** In game, `/mapedit` (permission `squaremappro.edit`) sends the player a clickable
   one-time link. Redeeming it opens a browser session tied to the player's UUID. This is the **only**
   command.
3. **Navigation (web only).** From/to on the map → route along the drawn road + rail network, with
   straight-line walking legs to/from the network. No commands, no terrain pathfinding.
4. **One deployable.** The web app is a static Next.js export bundled in the mod jar; Javalin serves the
   site, the API and squaremap's tiles (read from `Squaremap#webDir()`) on one port.

The previous plan (terrain A*, region graph, `/nav`, `/navbuild`, route schema v1) was built in
commits `b3be0f7`..`7c5ec89` and is superseded. Its task log is in git history (`git show 7c5ec89:PLAN.md`).

## Tasks

| #  | Status | Module     | Task                                                                                                  | Acceptance |
|----|--------|------------|-------------------------------------------------------------------------------------------------------|------------|
| 1  | `[x]`  | all        | **Cleanup + rename.** Delete terrain pathfinding (`nav-core` `path/ hierarchy/ region/ simplify/`, `WorldView`, `FixtureWorld`) and `nav-fabric` `world/ graph/ command/ route/ mixin/` + mixin json. Rename modules `nav-core`→`map-core`, `nav-fabric`→`map-fabric`, package `dev.nelsongx.nav`→`dev.nelsongx.map`. Keep: Gradle/Loom skeleton, Javalin lifecycle + config, squaremap layer plumbing, web CRS/tiles/`MapView`/`directions.ts`. Update `fabric.mod.json` description. | `./gradlew clean build` green; `cd web && npm test` green; `git grep -n "navbuild\|RegionGraph\|WorldView"` empty |
| 2  | `[ ]`  | map-core   | **Feature model + validation.** `Feature` sealed: `Building(polygon, name, category, description)`, `Road(polyline, name, roadClass)`, `Railway(polyline, name, colour)`, `Station(point, name, railwayId)`. Integer block `x,z` vertices. Validation: min vertex counts, no zero-length segments, simple (non-self-intersecting) building rings, station must lie on a vertex of its railway, name length limits. | `./gradlew :map-core:test` |
| 3  | `[ ]`  | map-core   | **SQLite `FeatureStore`** per server: `<world save>/data/squaremap-pro/map.sqlite`. CRUD per world id, monotonically increasing `revision` per feature (optimistic concurrency → `Conflict`), `created_by/updated_by` UUID + timestamps, append-only `feature_history`, `schema_version` table. Deleting a railway with stations is rejected. | `./gradlew :map-core:test` — CRUD, conflict, reload after reopen, history rows |
| 4  | `[ ]`  | map-core   | **Network + router.** Build graph from features: roads join **only at shared vertices** (crossing without a shared vertex = bridge/tunnel); rail boarded/left **only at stations**; walking legs = straight line from the endpoint to the nearest point on any road segment (projected) or a station, plus direct walk start→goal. Cost = time (config speeds: walk, per road class, rail). Dijkstra/A* with Euclidean/max-speed heuristic. Output legs (mode, points, distance, duration, name). Pure function over an immutable network snapshot. | `./gradlew :map-core:test` — direct walk wins when short, road preferred when faster, rail only via stations, disconnected → `no_path`, bridge does not connect |
| 5  | `[ ]`  | map-fabric | **Permissions + `/mapedit`.** `fabric-permissions-api` check `squaremappro.edit` (fallback op level 2). Command issues single-use token (256-bit random, 5 min TTL, in memory), replies with clickable `ClickEvent.OpenUrl` to `<http.publicUrl>/auth/redeem?token=…`. Sessions persisted in SQLite (configurable TTL, default 7 d), ended via web logout (single-command rule). Must run on the server thread; permission lookups for offline players use the async API. | `./gradlew build` + unit tests for token/session store |
| 6  | `[ ]`  | map-fabric | **HTTP API** (schemas in CLAUDE.md): auth redeem/me/logout, worlds list, features GET (public) and POST/PUT/DELETE (session cookie + `X-Requested-With` CSRF header + permission re-check), route v2. Static serving: bundled web export at `/`, squaremap tiles at `/tiles/*` from `Squaremap#webDir()`. Config: bind, port, `publicUrl`. Store + routing on the executor; handlers never touch the level. | `./gradlew build` + handler tests against an in-memory store |
| 7  | `[ ]`  | web        | **Static export + viewer.** `output: "export"`; replace the Next API mock with a test-only fixture client. World switcher, render features over tiles (styled by type/class), click → info card, search features by name. | `cd web && npm test && npm run build` |
| 8  | `[ ]`  | web        | **Editor.** Login state from `/api/auth/me`; toolbar to draw/edit/delete building/road/railway/station with vertex snapping (so roads connect), properties panel, 409 conflict → reload prompt, validation errors shown inline. Hidden when not logged in. | `cd web && npm test && npm run build` |
| 9  | `[ ]`  | web        | **Navigation panel** on route v2: pick from/to by map click or feature search, mode toggles (walk/road/rail), leg list with durations, turn-by-turn from leg points, route drawn per mode. | `cd web && npm test && npm run build` |
| 10 | `[ ]`  | map-fabric | **Bundle + squaremap mirror.** Gradle copies `web/out` into the jar resources. Optional (config) mirror of features into a squaremap `SimpleLayerProvider` so squaremap's own page shows them too. | `./gradlew build`; jar contains `web/index.html` |
| 11 | `[ ]`  | all        | **Live smoke test** (never done for the old plan): dev server with squaremap-fabric 1.3.12 + fabric-permissions-api; verify nested Javalin/Jetty/Kotlin jars load, `/mapedit` link → edit → reload persists → route works. | Lead runs it; report with screenshots/logs |

## Decisions log

- 2026-09-14 — squaremap reference revision pinned to tag `v1.3.12` (last 1.21.11 release). Mappings: Mojmap.
- 2026-09-15 — **Re-scope** (user: "a comprehensive map system with panel that can add/mark building, roads
  and railroads … opened by user with permission in-game with command … navigation … web-only and no
  commands"). User choices:
  - Routing: drawn road/rail network + straight-line walking legs (terrain A* dropped).
  - Editor access: one-time login link from an in-game command, session tied to player UUID.
  - Permission: `fabric-permissions-api` (approved new dependency), fallback op level.
  - Hosting: web exported statically and served by the mod.
- 2026-09-15 — Tiles served from `Squaremap#webDir()` (api; impl `DirectoryProvider.webDirectory()` →
  `<dataDir>/<settings.web-directory.path>`, tiles under `tiles/`, v1.3.12).

## Open questions for the user

None. User (2026-09-15): "all approved, go ahead with all task, you might ask agents to help you. commit yourself."
Approved: route schema v2 + feature API v1 (CLAUDE.md), `@geoman-io/leaflet-geoman-free`, defaults (road classes
`highway/main/street/path`; building categories `residential/commercial/public/industrial/other`; no one-way roads;
any editor may edit/delete any feature, history records who), module/package rename.

## Task log

- **Task 1 — done 2026-09-15.** Lead-verified: `./gradlew clean build` green (map-core 1 test, map-fabric 11), web `npm test` 69 passed, stale-name grep and purity grep empty. map-fabric now: `MapFabricMod`, `MapExecutor`, `http/` (`HttpConfig`, `HttpLifecycle`, `MapHttpServer` with only `GET /api/health`), `squaremap/` (`MapLayer`/`MapLayers`/`NoopMapLayer`/`SquaremapMapLayer` with server-thread `ensureLayer(worldId)`, `MapGeometry`). `HttpConfig.timeoutMs/maxConcurrent` kept for Task 6. Leftover gitignored `nav-fabric/run/` locked by an IDE — delete manually.
