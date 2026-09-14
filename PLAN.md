# PLAN

Status markers: `[ ]` todo · `[~]` in progress · `[x]` done (lead-verified) · `[!]` blocked / escalated

Rules for every task: see `CLAUDE.md` (versions, Mojmap, nav-core purity, threading, route schema).
Lead verification after each task: run the acceptance command myself, purity grep on `nav-core`,
check no `ServerLevel` access off the server thread, update this file, stop and report.

| #  | Status | Module     | Task                                                                                                  | Acceptance (run by subagent until green, then re-run by lead) |
|----|--------|------------|-------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| 1  | `[x]`  | root, nav-core, nav-fabric | Gradle multi-module skeleton, Loom, squaremap-api compileOnly, `fabric.mod.json` declaring squaremap | `./gradlew build` |
| 2  | `[x]`  | nav-core   | Interfaces only: `WorldView` (groundY, walkable), sealed `PathResult` (success w/ points \| failure w/ reason), `FixtureWorld` parsing ASCII-art multi-layer worlds | `./gradlew :nav-core:test` (fixture-parser tests) |
| 3  | `[x]`  | nav-core   | A* over `WorldView`: octile heuristic, node cap, pure function                                        | `./gradlew :nav-core:test` — straight line, wall with gap, unreachable, cap exceeded |
| 4  | `[x]`  | nav-core   | Path simplification: line-of-sight string pull + Douglas-Peucker                                      | `./gradlew :nav-core:test` — 200-node staircase → <10 points |
| 5  | `[x]`  | nav-core   | Region graph: partition into regions, inter-region links, SQLite serialization                        | `./gradlew :nav-core:test` — build, reload, assert identical |
| 6  | `[x]`  | nav-core   | Hierarchical query: coarse region route + fine search at endpoints                                    | `./gradlew :nav-core:test` — endpoints match flat A* on a feasible fixture |
| 7  | `[x]`  | nav-fabric | `WorldView` adapter over `ServerLevel`: snapshot chunks on server thread, read off-thread; vanilla `isPathfindable(..., PathComputationType.LAND)` as walkability base; threading boundary commented | `./gradlew build` |
| 8  | `[~]`  | nav-fabric | `/nav <x> <z>` Brigadier command + squaremap `SimpleLayerProvider` polyline; handle squaremap absent, no path, cap exceeded | `./gradlew build` |
| 9  | `[ ]`  | nav-fabric | `/navbuild` throttled region-graph builder + invalidation on block change                             | `./gradlew build` |
| 10 | `[ ]`  | nav-fabric | Javalin `GET /route?from=&to=` returning the CLAUDE.md schema                                         | `./gradlew build` |
| 11 | `[ ]`  | web        | Next.js + Leaflet, squaremap tiles, directions panel, mock endpoint; CRS/transform from squaremap frontend source | `cd web && npm test` (+ `npm run build`) |

## Decisions log

- 2026-09-14 — squaremap clone is on MC 26.2; reference revision pinned to tag `v1.3.12` (last 1.21.11 release). `api/` unchanged v1.3.12..master.
- 2026-09-14 — Mappings: Mojmap (matches squaremap v1.3.12 fabric build).
- 2026-09-14 — Route schema v1 defined in CLAUDE.md. Approved (user: "do what's most applicable"): optional `world` param, server-resolved Y, no server-side steps (web derives turns from `points`).
- 2026-09-14 — squaremap declared as `suggests` in fabric.mod.json (optional; Task 8 handles absence).
- 2026-09-14 — Minecraft signatures are read from Loom `genSources` output and quoted in reports.

## Open questions for the user

None. User (2026-09-14): "finish all and commit each stage yourself" — run tasks 2–11 back-to-back, lead commits after each verified task.

## Task log

- **Task 1 — done 2026-09-14.** Lead-verified: `./gradlew clean build` green, `:nav-core:test` green (smoke test asserts JDK 21), purity grep empty, mod jar nests `nav-core` via `include`, `fabric.mod.json` has `suggests: squaremap >=1.3.12`.
  Resolved versions: Gradle 9.2.1 (squaremap v1.3.12's wrapper), `fabric-loom` 1.13.6, MC 1.21.11, Loader 0.18.4, Fabric API 0.141.3+1.21.11, squaremap-api 1.3.12, JUnit 5.13.4.
  Notes: `gradle.properties` pins `org.gradle.java.installations.paths` to this machine's JDK 21 path (machine-specific). Harmless Loom config warning "Cannot remap modifiers…". squaremap tag v1.3.12 itself built against Loader 0.18.2 / API 0.139.4; we use the newer f3e6f72 values.
- **Task 2 — done 2026-09-14.** Lead-verified: clean build green; 22 tests (FixtureWorldTest 17, PathResultTest 4, smoke 1), 0 failures; purity grep empty. `FixtureWorld` lives in `testFixtures` (format: `y=<n>` headers, `#` solid, `.` air, `~` fluid, `A–Z` markers, `;` comments). `maxY` = top layer + 2.
- **Task 3 — done 2026-09-14.** Lead-verified: clean build green; 57 tests, 0 failures; purity grep empty; no static mutable state. `MovementModel` (8-dir, step up 1, drop 3, no corner cutting, costs 1/√2/+0.5 up/+0.25 per block down) is the single source of legal moves; `AStar.findPath(world, start, goal, maxNodes)` verified optimal vs brute-force Dijkstra on seeded random worlds.
  Lead-requested fix in the same task: first version let drops/step-ups pass through solid blocks. **`WorldView` gained `boolean passable(x,y,z)`** (Task 7 adapter must implement it); step up requires headroom at mover y+2, drops require a clear target column.
- **Task 4 — done 2026-09-14.** Lead-verified: clean build green; 75 tests, 0 failures; purity grep empty. `LineOfSight.clear` walks a supercover line using `MovementModel` moves (so simplified segments are followable); `PathSimplifier.simplify(world, path[, epsilon[, maxLookahead]])` = string pull + LOS-constrained Douglas-Peucker. 200-node staircase → 2 points; climbing staircase 48 → 2.
- **Task 5 — done 2026-09-14.** Lead-verified: clean build green; 117 tests, 0 failures; purity grep empty. Regions = mutual-move connected components per sector (default 16); `RegionLink` = every directed legal move between different regions; deterministic indices by (y,z,x) seed. `RegionGraph` immutable with `locate` and `withSectorsRebuilt` (rebuild ≡ full build, incl. 8-neighbour link recompute). `RegionGraphStore` save/load SQLite (`sqlite-jdbc 3.53.4.0`, atomic tmp+move, `format_version=1`).
  **Carry-forward for Task 9:** nav-fabric must also `include("org.xerial:sqlite-jdbc:3.53.4.0")` — `include(project(":nav-core"))` does not nest transitive deps.
- **Task 6 — done 2026-09-14.** Lead-verified: clean build green; 132 tests, 0 failures; purity grep empty. `HierarchicalPathfinder` (coarse A* over portal states → per-segment A*, flat fallbacks for near / same-region / unlocatable / stale) and `Router.route(world, graph|null, start, goal, opts)` = search + `PathSimplifier`. Acceptance 64×64 fixture: same endpoints and cost as flat (140.975) with 868 vs 2,052 expansions; unreachable rejected with 1,336 vs 3,692.
- **Task 7 — done 2026-09-14.** Lead-verified: clean build green; 147 tests total (nav-fabric 15), 0 failures; purity grep empty; no Yarn names; spot-checked quoted signatures against Loom Mojmap sources (`BlockStateBase#isPathfindable(PathComputationType)`, `ServerChunkCache#getChunkNow` returns null off main thread and never loads, `PalettedContainer#get/maybeHas`, `LevelHeightAccessor#getMaxY`). Server-thread entry points assert `isSameThread()`; off-thread classes (`SnapshotWorldView`, `ChunkSnapshot`, `BlockClass`, `NavExecutor`) import no MC/Fabric types. `SnapshotCache.prepare` (any thread) → drained in `END_SERVER_TICK` (2 ms / 64 chunks per tick) → completes via `thenApplyAsync` on `NavExecutor`.
  Behaviour choice: closed doors are BLOCKED (vanilla LAND); dirt path/farmland standable.
