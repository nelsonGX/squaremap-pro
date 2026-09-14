# squaremap-pro web

Next.js (App Router) + Leaflet navigation UI on top of squaremap's tiles.

## Commands

```sh
npm install
npm run dev        # http://localhost:3000
npm test           # vitest run (pure unit tests in lib/)
npm run build      # production build
npx tsc --noEmit   # type check
npm run lint       # eslint (eslint-config-next)
```

## Configuration

Both variables are read at build time (`NEXT_PUBLIC_*`), e.g. in `.env.local`.

| Variable | Example | When unset |
|----------|---------|------------|
| `NEXT_PUBLIC_SQUAREMAP_URL` | `http://localhost:8080` | Uses the bundled mock `public/mock-squaremap` (`tiles/settings.json` + per-world `tiles/<world>/settings.json`). There are no tiles; missing tiles are replaced by a transparent image over a grid background. |
| `NEXT_PUBLIC_ROUTE_API` | `http://127.0.0.1:8765` | Uses the mock route handler at `/api/mock/route`. |

The client requests `${NEXT_PUBLIC_ROUTE_API}/route?from=x,z&to=x,z&world=namespace:path`. When
pointing at the mod, add the web origin (e.g. `http://localhost:3000`) to the mod's
`http.cors.origins`. squaremap tiles are loaded as `<img>` elements, which need no CORS.

The squaremap world *web name* (`minecraft_overworld`) is converted to the route API world id by
replacing the first `_` with `:` (squaremap writes `identifier.toString().replace(":", "_")`, which
is not reversible for namespaces containing `_`).

## Mock route endpoint

`GET /api/mock/route?from=x,z&to=x,z[&world=]` — same validation and HTTP status codes as the mod
(schema v1, see the repo's `CLAUDE.md`). Deterministic fake routes. Special cases:

| Request | Result |
|---------|--------|
| `world` not in the mock settings.json | 404 `world_not_found` |
| `to` x = 13 | 200 `no_path` |
| `to` x = 14 | 200 `cap_exceeded` |
| `to` x = 15 | 503 `not_ready` |

## Layout

- `lib/squaremapCrs.ts` — block ↔ LatLng transform ported from squaremap v1.3.12 `Squaremap.js`.
- `lib/squaremapSettings.ts` — squaremap `settings.json` shapes and guards.
- `lib/routeSchema.ts` — schema v1 type guard.
- `lib/routeClient.ts` — URL building, input parsing, response classification, fetch.
- `lib/directions.ts` — turn-by-turn steps derived from `points` (left/right derivation documented in the file).
- `lib/mockRoute.ts` — pure mock endpoint; `app/api/mock/route/route.ts` is a thin wrapper.
- `components/NavApp.tsx` — directions panel; `components/MapView.tsx` — Leaflet map (client-only via `next/dynamic`, `ssr: false`).
