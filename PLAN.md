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
| 2  | `[x]`  | map-core   | **Feature model + validation.** `Feature` sealed: `Building(polygon, name, category, description)`, `Road(polyline, name, roadClass)`, `Railway(polyline, name, colour)`, `Station(point, name, railwayId)`. Integer block `x,z` vertices. Validation: min vertex counts, no zero-length segments, simple (non-self-intersecting) building rings, station must lie on a vertex of its railway, name length limits. | `./gradlew :map-core:test` |
| 3  | `[x]`  | map-core   | **SQLite `FeatureStore`** per server: `<world save>/data/squaremap-pro/map.sqlite`. CRUD per world id, monotonically increasing `revision` per feature (optimistic concurrency → `Conflict`), `created_by/updated_by` UUID + timestamps, append-only `feature_history`, `schema_version` table. Deleting a railway with stations is rejected. | `./gradlew :map-core:test` — CRUD, conflict, reload after reopen, history rows |
| 4  | `[x]`  | map-core   | **Network + router.** Build graph from features: roads join **only at shared vertices** (crossing without a shared vertex = bridge/tunnel); rail boarded/left **only at stations**; walking legs = straight line from the endpoint to the nearest point on any road segment (projected) or a station, plus direct walk start→goal. Cost = time (config speeds: walk, per road class, rail). Dijkstra/A* with Euclidean/max-speed heuristic. Output legs (mode, points, distance, duration, name). Pure function over an immutable network snapshot. | `./gradlew :map-core:test` — direct walk wins when short, road preferred when faster, rail only via stations, disconnected → `no_path`, bridge does not connect |
| 5  | `[x]`  | map-fabric | **Permissions + `/mapedit`.** `fabric-permissions-api` check `squaremappro.edit` (fallback op level 2). Command issues single-use token (256-bit random, 5 min TTL, in memory), replies with clickable `ClickEvent.OpenUrl` to `<http.publicUrl>/auth/redeem?token=…`. Sessions persisted in SQLite (configurable TTL, default 7 d), ended via web logout (single-command rule). Must run on the server thread; permission lookups for offline players use the async API. | `./gradlew build` + unit tests for token/session store |
| 6  | `[x]`  | map-fabric | **HTTP API** (schemas in CLAUDE.md): auth redeem/me/logout, worlds list, features GET (public) and POST/PUT/DELETE (session cookie + `X-Requested-With` CSRF header + permission re-check), route v2. Static serving: bundled web export at `/`, squaremap tiles at `/tiles/*` from `Squaremap#webDir()`. Config: bind, port, `publicUrl`. Store + routing on the executor; handlers never touch the level. | `./gradlew build` + handler tests against an in-memory store |
| 7  | `[x]`  | web        | **Static export + viewer.** `output: "export"`; replace the Next API mock with a test-only fixture client. World switcher, render features over tiles (styled by type/class), click → info card, search features by name. | `cd web && npm test && npm run build` |
| 8  | `[x]`  | web        | **Editor.** Login state from `/api/auth/me`; toolbar to draw/edit/delete building/road/railway/station with vertex snapping (so roads connect), properties panel, 409 conflict → reload prompt, validation errors shown inline. Hidden when not logged in. | `cd web && npm test && npm run build` |
| 9  | `[x]`  | web        | **Navigation panel** on route v2: pick from/to by map click or feature search, mode toggles (walk/road/rail), leg list with durations, turn-by-turn from leg points, route drawn per mode. | `cd web && npm test && npm run build` |
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
- **Task 2 — done 2026-09-15.** Lead-verified: `:map-core:test` green (71 tests), purity grep empty. Package `dev.nelsongx.map.core.feature`: `Vertex`, `FeatureType`/`RoadClass`/`BuildingCategory` (wire names), sealed `FeatureData` (`BuildingData`/`RoadData`/`RailwayData`/`StationData`), `Feature`, `Actor`, `ValidationError` (JSON field names), `RailwayLookup`, `FeatureValidator`. Choices: names stripped at construction (building name/description null → ""); buildings also capped at 2000 vertices; name length in code points; roads/railways may self-cross; collinear pass-through vertices allowed in rings.
- **Task 3 — done 2026-09-15.** Lead-verified: `:map-core:test` green (107 tests), purity grep empty. `dev.nelsongx.map.core.store.FeatureStore` (`open(Path[, Clock])`, `openInMemory`, list/get/history, create/update/delete → sealed `Result` Ok/Invalid/NotFound/Conflict/Rejected). All methods `synchronized` on one connection (blocking → worker executor). WAL, `schema_version`=1, props as nullable columns, geometry text `x,z;x,z`, append-only `feature_history` enforced by triggers (delete row uses revision+1). Driver instantiated via `new org.sqlite.JDBC().connect` (avoids `DriverManager` under the mod class loader). Railway edit dropping a station vertex → Invalid(geometry); deleting railway with stations → Rejected.
- **Task 7 — done 2026-09-15.** Lead-verified in `web/`: `npm test` 79 passed, `tsc --noEmit` clean, lint clean, `npm run build` → `out/index.html`. Static export; `lib/api/` (`ApiClient`, `HttpApiClient`, `FixtureApiClient`, hand-written guards incl. route v2), `lib/features/` (styles, search, geometry, layers), `ViewerApp` + `FeatureCard` + canvas `MapView`; `.env.development` enables fixtures for `next dev`. Server must serve: `web/out` at `/`; `/tiles/<web name>/{z}/{x}_{y}.png` and `/tiles/<web name>/settings.json` from squaremap `<webDir>/tiles` (web name = world id with `:`→`_`); `/api/worlds/{world}/features` with literal `:` in the path segment.
  **Not yet visually checked in a browser** (Chrome extension unavailable to the agent) — covered in Task 11.
- **Task 4 — done 2026-09-15.** Lead-verified: `:map-core:test` green (134 tests), purity grep empty. `dev.nelsongx.map.core.route`: `Mode`, `Speeds` (defaults walk/path 4.317, street 5.612, main 7.0, highway 9.0, rail 8.0), `RouterOptions` (maxAccessWalk 256, accessCandidates 4, transferWalk 32, stationRoadLink 32, maxDirectWalk +∞), immutable `Network.build(features)` (64-block uniform grid), `Router.route` → `Found(legs)`/`NoPath`. A* on time, heuristic = euclid / fastest allowed speed, verified = Dijkstra on 600 random queries; 20k-segment lattice routes in ≪ 2 s. Choices: WALK always on (modes gate ROAD/RAIL only); a station serves every railway with a vertex at its point (interchange); station↔road link to nearest road vertex; access/egress/transfer links computed per request; no boarding penalty; same from/to → one zero-length walk leg.
- **Task 8 — done 2026-09-16.** Lead-verified in `web/`: `npm test` 114 passed, tsc/lint clean, build green. `@geoman-io/leaflet-geoman-free` 2.20.1 (MIT, client-only, opt-in mode). `lib/editor/` (block rounding, 14 px vertex snapping for road/railway, station placement within 18 px of a railway vertex, client port of `FeatureValidator`, draft reducer, error mapping, `?edit=1`/`?authError=` parsing), `EditorPanel`, unsaved-changes guard, 409 "Changed by X" with reload/keep, 401/403 → view mode. Expectations for Task 6: 409 body includes `"current": Feature`; 422 code `railway_has_stations`.
  **Not yet exercised in a browser** (Chrome extension timed out) — Task 11.
- **Task 5 — done 2026-09-16.** (Interrupted once by an API rate limit, resumed.) Lead-verified: `./gradlew build` green (map-fabric 37 tests), Yarn grep + purity grep empty, no blocking `join/get` on futures. `me.lucko:fabric-permissions-api:0.6.1` (last release for 1.21.11; 0.7.0 = 26.1; Maven Central + GitHub releases), nested via `include`, `depends` in fabric.mod.json. `auth/` (`TokenStore` in memory 5 min single-use; `SessionStore` SQLite `<world>/data/squaremap-pro/sessions.sqlite`, SHA-256 of id stored, fixed TTL; `FabricPermissionChecker.canEdit(Actor)` online → `Permissions.check`, offline → `getPermissionValue` then op-level fallback on server thread; `AuthServices`), `command/MapEditCommand` (`Permissions.require("squaremappro.edit", PermissionLevel.GAMEMASTERS)`, link sent only to the player, not via `sendSuccess`). Config adds `http.publicUrl`, `auth.sessionTtlHours` (168), `auth.cookieSecure` (auto from https); missing keys appended to existing files.
- **Task 9 — done 2026-09-16.** Lead-verified in `web/`: `npm test` 136 passed, tsc/lint clean, build green. Explore / Directions / Edit tabs; `DirectionsPanel` (coords or place search, pick-on-map, swap, Roads/Railways toggles — walking always on, summary, leg list with rail board/alight, turn-by-turn per walk/road leg); per-mode route styles, draggable A/B markers, 250 ms debounce + AbortController; `?world=&from=&to=` URL state. `lib/navigation/` pure helpers; fixture router in `lib/api/fixtureRoute.ts`. `modes` omitted when both toggles on. Rail leg colour looked up by railway name.
  **Not yet exercised in a browser** — Task 11.
- **Task 6 — done 2026-09-16.** Lead-verified: `./gradlew build` green (map-fabric 57 tests), Yarn + purity greps empty, no Minecraft imports under `http/` (handlers depend on `ApiServices`: executor, stores, `PermissionChecker`, `WorldDirectory`). All CLAUDE.md endpoints + `/tiles/*` (squaremap `webDir()/tiles`, traversal + symlink escape blocked) + static site from classpath `web/` (Task 10). World list re-snapshotted on the server thread every 100 ticks (volatile). Network cached per world, dropped on successful write. Permission re-check cached ≤ 30 s. 1 MiB body cap, timeout/busy → 503. CORS default now empty (same-origin). New config `route.speed.*`, `route.maxDirectWalk`. Additions documented in CLAUDE.md: 413, 503 `not_ready|timeout|busy`, `authError=unavailable`. A timed-out write may still commit.
