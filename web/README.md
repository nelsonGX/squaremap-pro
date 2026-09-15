# squaremap-pro web

Next.js (App Router, `output: "export"`) + Leaflet map viewer on top of squaremap's tiles. The
production build is a static site in `web/out`, served by the mod at `/`.

## Commands

```sh
npm install
npm run dev        # http://localhost:3000 — uses the fixture API (.env.development)
npm test           # vitest run (pure unit tests in lib/)
npx tsc --noEmit   # type check
npm run lint       # eslint (eslint-config-next)
npm run build      # static export → out/ (out/index.html)
```

## Configuration (build time, `NEXT_PUBLIC_*`)

| Variable | Default | Meaning |
|----------|---------|---------|
| `NEXT_PUBLIC_API_BASE` | `""` (same origin) | Prefix for `/api/...` calls. |
| `NEXT_PUBLIC_TILES_BASE` | `/tiles` | squaremap's `<webDir>/tiles` over HTTP. |
| `NEXT_PUBLIC_USE_FIXTURES` | unset (`1` in `.env.development`) | `1` = in-memory `FixtureApiClient`, no tiles, no settings fetch. |
| `NEXT_PUBLIC_FIXTURE_LOGGED_IN` | unset (= logged in) | Fixture mode only: `0` = the fixture reports "logged out". |

To run `next dev` against a real server, create `.env.development.local` with
`NEXT_PUBLIC_USE_FIXTURES=0`, `NEXT_PUBLIC_API_BASE=http://host:port`, `NEXT_PUBLIC_TILES_BASE=http://host:port/tiles`.
Cross-origin requests do not carry the session cookie (`credentials: "same-origin"`), so editing
only works from the same origin.

## Paths the server must serve

| Path | Source |
|------|--------|
| `/`, `/index.html`, `/404.html`, `/_next/**`, `/*.txt` | `web/out` (static export) |
| `/tiles/<web name>/{z}/{x}_{y}.png` | squaremap `<webDir>/tiles` (web name = world id with `:` → `_`) |
| `/tiles/<web name>/settings.json` | same (zoom `max/def/extra`, `spawn`; defaults 3/3/2 if missing) |
| `GET /api/worlds`, `GET /api/worlds/{world}/features`, `GET /api/auth/me` | mod API (world id kept readable, e.g. `/api/worlds/minecraft:overworld/features`) |
| `POST /api/auth/logout`, `POST/PUT/DELETE /api/worlds/{world}/features[/{id}]` | mod API; the web sends `X-Requested-With: squaremap-pro` and same-origin cookies. Feature id is percent-encoded. A 409 body may include `"current": Feature` (used for "Changed by X at T"; otherwise the list is refetched). |
| `GET /api/route?world=&from=x,z&to=x,z[&modes=walk,road,rail]` | route schema v2; the web expects the body on 200 (`ok`/`no_path`), 400 (`invalid_request`) and 404 (`world_not_found`). `modes` is omitted when roads and railways are both enabled. |
| `/?world=<id>&from=x,z&to=x,z` | shareable directions state, restored on load and kept current with `history.replaceState` |
| `/?edit=1`, `/?authError=expired` | redirect targets of `/api/auth/redeem` (the page strips them with `history.replaceState`) |

## Layout

- `lib/api/types.ts` — Feature API v1, auth and route schema v2 types (CLAUDE.md).
- `lib/api/guards.ts` — hand-written response validation (`parseWorlds`, `parseFeatureList`, `parseRouteResponse`, …).
- `lib/api/client.ts` — `ApiClient` interface, `ApiError`, `HttpApiClient` (CSRF header on non-GET).
- `lib/api/fixtures.ts` — `FixtureApiClient` + fixture data (validated by tests).
- `lib/features/` — styles, search, geometry → LatLng / bounds, layer visibility.
- `lib/editor/` — rounding + vertex snapping + station placement (`geometry.ts`), client mirror of map-core `FeatureValidator` (`validate.ts`), draft reducer + request building (`draft.ts`), error mapping (`errors.ts`), `?edit`/`?authError` parsing (`authQuery.ts`).
- `lib/navigation/` — coordinate parsing and feature → routing point (`coords.ts`), duration/distance formatting (`format.ts`), URL state (`urlState.ts`), route request helpers and leg presentation/styles (`legs.ts`). `lib/api/fixtureRoute.ts` builds canned v2 routes from fixture features (special inputs: unknown world → `world_not_found`, |coord| > 30,000,000 → `invalid_request`, `to=13,13` → `no_path`).
- `components/DirectionsPanel.tsx` — from/to fields (coordinates, place search, pick on map), swap, mode toggles, summary, legs with turn-by-turn.
- `components/EditorPanel.tsx` — add toolbar, properties form, save/cancel/delete, conflict prompt. Geometry editing uses leaflet-geoman in `MapView.tsx` (opt-in mode: only the map and the draft layer get geoman handlers).
- `lib/squaremapCrs.ts`, `lib/squaremapSettings.ts` — squaremap v1.3.12 CRS and settings.
- `lib/directions.ts` — turn-by-turn from `{x,z}` points (for the Task 9 navigation panel).
- `components/ViewerApp.tsx` — panel (search, world switcher, layer chips, info card); `components/MapView.tsx` — Leaflet map (client-only via `next/dynamic`).
