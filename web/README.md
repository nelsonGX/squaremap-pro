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

## Layout

- `lib/api/types.ts` — Feature API v1, auth and route schema v2 types (CLAUDE.md).
- `lib/api/guards.ts` — hand-written response validation (`parseWorlds`, `parseFeatureList`, `parseRouteResponse`, …).
- `lib/api/client.ts` — `ApiClient` interface, `ApiError`, `HttpApiClient` (CSRF header on non-GET).
- `lib/api/fixtures.ts` — `FixtureApiClient` + fixture data (validated by tests).
- `lib/features/` — styles, search, geometry → LatLng / bounds, layer visibility.
- `lib/squaremapCrs.ts`, `lib/squaremapSettings.ts` — squaremap v1.3.12 CRS and settings.
- `lib/directions.ts` — turn-by-turn from `{x,z}` points (for the Task 9 navigation panel).
- `components/ViewerApp.tsx` — panel (search, world switcher, layer chips, info card); `components/MapView.tsx` — Leaflet map (client-only via `next/dynamic`).
