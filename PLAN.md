# PLAN

Status markers: `[ ]` todo · `[~]` in progress · `[x]` done (lead-verified) · `[!]` blocked / escalated

Rules for every task: see `CLAUDE.md` (versions, Mojmap, nav-core purity, threading, route schema).
Lead verification after each task: run the acceptance command myself, purity grep on `nav-core`,
check no `ServerLevel` access off the server thread, update this file, stop and report.

| #  | Status | Module     | Task                                                                                                  | Acceptance (run by subagent until green, then re-run by lead) |
|----|--------|------------|-------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| 1  | `[ ]`  | root, nav-core, nav-fabric | Gradle multi-module skeleton, Loom, squaremap-api compileOnly, `fabric.mod.json` declaring squaremap | `./gradlew build` |
| 2  | `[ ]`  | nav-core   | Interfaces only: `WorldView` (groundY, walkable), sealed `PathResult` (success w/ points \| failure w/ reason), `FixtureWorld` parsing ASCII-art multi-layer worlds | `./gradlew :nav-core:test` (fixture-parser tests) |
| 3  | `[ ]`  | nav-core   | A* over `WorldView`: octile heuristic, node cap, pure function                                        | `./gradlew :nav-core:test` — straight line, wall with gap, unreachable, cap exceeded |
| 4  | `[ ]`  | nav-core   | Path simplification: line-of-sight string pull + Douglas-Peucker                                      | `./gradlew :nav-core:test` — 200-node staircase → <10 points |
| 5  | `[ ]`  | nav-core   | Region graph: partition into regions, inter-region links, SQLite serialization                        | `./gradlew :nav-core:test` — build, reload, assert identical |
| 6  | `[ ]`  | nav-core   | Hierarchical query: coarse region route + fine search at endpoints                                    | `./gradlew :nav-core:test` — endpoints match flat A* on a feasible fixture |
| 7  | `[ ]`  | nav-fabric | `WorldView` adapter over `ServerLevel`: snapshot chunks on server thread, read off-thread; vanilla `isPathfindable(..., PathComputationType.LAND)` as walkability base; threading boundary commented | `./gradlew build` |
| 8  | `[ ]`  | nav-fabric | `/nav <x> <z>` Brigadier command + squaremap `SimpleLayerProvider` polyline; handle squaremap absent, no path, cap exceeded | `./gradlew build` |
| 9  | `[ ]`  | nav-fabric | `/navbuild` throttled region-graph builder + invalidation on block change                             | `./gradlew build` |
| 10 | `[ ]`  | nav-fabric | Javalin `GET /route?from=&to=` returning the CLAUDE.md schema                                         | `./gradlew build` |
| 11 | `[ ]`  | web        | Next.js + Leaflet, squaremap tiles, directions panel, mock endpoint; CRS/transform from squaremap frontend source | `cd web && npm test` (+ `npm run build`) |

## Decisions log

- 2026-09-14 — squaremap clone is on MC 26.2; reference revision pinned to tag `v1.3.12` (last 1.21.11 release). `api/` unchanged v1.3.12..master.
- 2026-09-14 — Mappings: Mojmap (matches squaremap v1.3.12 fabric build).
- 2026-09-14 — Route schema v1 defined in CLAUDE.md (pending approval).

## Open questions for the user

See the approval request in the conversation (pending).
