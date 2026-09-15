/**
 * Canned-but-plausible route v2 answers for the fixture API. Not the real router (map-core): it
 * compares a handful of simple candidates over the fixture features and returns the fastest.
 *
 * Candidates: direct walk; walk → one road between its two vertices nearest from/to → walk;
 * walk → one railway between the stations nearest from/to (same railway) → walk. Speeds are
 * map-core's defaults (blocks/s): walk 4.317, path 4.317, street 5.612, main 7, highway 9, rail 8.
 *
 * Special inputs (for tests and UI states):
 * - unknown world → `world_not_found`
 * - any |coordinate| > 30,000,000 → `invalid_request`
 * - `to` = (13, 13) → `no_path`
 * - from = to → single zero-length walk leg `[from, from]`
 */
import type { Feature, RoadClass, RouteLeg, RouteRequest, RouteResponse, XZ } from "./types";

export const FIXTURE_NO_PATH_TO: XZ = { x: 13, z: 13 };
const MAX_COORDINATE = 30_000_000;

const WALK_SPEED = 4.317;
const RAIL_SPEED = 8;
const ROAD_SPEED: Record<RoadClass, number> = { path: 4.317, street: 5.612, main: 7, highway: 9 };

const dist = (a: XZ, b: XZ) => Math.hypot(a.x - b.x, a.z - b.z);
const same = (a: XZ, b: XZ) => a.x === b.x && a.z === b.z;

function pathLength(points: readonly XZ[]): number {
  let d = 0;
  for (let i = 1; i < points.length; i++) d += dist(points[i - 1]!, points[i]!);
  return d;
}

function nearestIndex(points: readonly XZ[], p: XZ): number {
  let best = 0;
  for (let i = 1; i < points.length; i++) if (dist(points[i]!, p) < dist(points[best]!, p)) best = i;
  return best;
}

/** Vertices i..j (either direction), inclusive. */
function slice(points: readonly XZ[], i: number, j: number): XZ[] {
  return i <= j ? points.slice(i, j + 1) : points.slice(j, i + 1).reverse();
}

function walkLeg(a: XZ, b: XZ): RouteLeg {
  const d = dist(a, b);
  return { mode: "walk", name: null, points: [{ ...a }, { ...b }], distance: d, duration: d / WALK_SPEED };
}

function round(leg: RouteLeg): RouteLeg {
  return { ...leg, distance: Math.round(leg.distance * 10) / 10, duration: Math.round(leg.duration * 10) / 10 };
}

/** Walk legs of zero length are dropped (a route always keeps at least one leg). */
function compose(legs: RouteLeg[]): RouteLeg[] {
  const kept = legs.filter((l) => l.mode !== "walk" || l.distance > 0);
  return kept.length > 0 ? kept : legs.slice(0, 1);
}

function total(legs: readonly RouteLeg[]) {
  return legs.reduce((s, l) => ({ distance: s.distance + l.distance, duration: s.duration + l.duration }), { distance: 0, duration: 0 });
}

function empty(req: RouteRequest, status: RouteResponse["status"], error: string, withEndpoints: boolean): RouteResponse {
  return {
    schemaVersion: 2,
    status,
    world: req.world,
    from: withEndpoints ? { ...req.from } : null,
    to: withEndpoints ? { ...req.to } : null,
    legs: [],
    distance: 0,
    duration: 0,
    error,
  };
}

export function fixtureRoute(req: RouteRequest, worldExists: boolean, features: readonly Feature[]): RouteResponse {
  if (!worldExists) return empty(req, "world_not_found", `world ${req.world} is not loaded`, false);
  const coords = [req.from.x, req.from.z, req.to.x, req.to.z];
  if (coords.some((c) => !Number.isSafeInteger(c) || Math.abs(c) > MAX_COORDINATE)) {
    return empty(req, "invalid_request", `coordinates must be integers within ±${MAX_COORDINATE}`, false);
  }
  if (same(req.to, FIXTURE_NO_PATH_TO)) return empty(req, "no_path", "no route (fixture)", true);

  const from = req.from;
  const to = req.to;
  const useRoad = !req.modes || req.modes.includes("road");
  const useRail = !req.modes || req.modes.includes("rail");

  if (same(from, to)) {
    return { ...empty(req, "ok", "", true), legs: [{ mode: "walk", name: null, points: [{ ...from }, { ...from }], distance: 0, duration: 0 }], error: null };
  }

  const candidates: RouteLeg[][] = [[walkLeg(from, to)]];

  if (useRoad) {
    for (const r of features) {
      if (r.type !== "road") continue;
      const i = nearestIndex(r.geometry, from);
      const j = nearestIndex(r.geometry, to);
      if (i === j) continue;
      const pts = slice(r.geometry, i, j);
      const d = pathLength(pts);
      candidates.push(
        compose([
          walkLeg(from, pts[0]!),
          { mode: "road", name: r.name, points: pts.map((p) => ({ ...p })), distance: d, duration: d / ROAD_SPEED[r.props.roadClass] },
          walkLeg(pts[pts.length - 1]!, to),
        ]),
      );
    }
  }

  if (useRail) {
    const stations = features.filter((f) => f.type === "station");
    for (const rw of features) {
      if (rw.type !== "railway") continue;
      const own = stations.filter((s) => s.type === "station" && s.props.railwayId === rw.id);
      if (own.length < 2) continue;
      const a = own.reduce((b, s) => (dist(s.geometry[0]!, from) < dist(b.geometry[0]!, from) ? s : b));
      const b = own.reduce((c, s) => (dist(s.geometry[0]!, to) < dist(c.geometry[0]!, to) ? s : c));
      if (a.id === b.id) continue;
      const i = rw.geometry.findIndex((v) => same(v, a.geometry[0]!));
      const j = rw.geometry.findIndex((v) => same(v, b.geometry[0]!));
      if (i < 0 || j < 0) continue;
      const pts = slice(rw.geometry, i, j);
      const d = pathLength(pts);
      candidates.push(
        compose([
          walkLeg(from, pts[0]!),
          {
            mode: "rail",
            name: rw.name,
            points: pts.map((p) => ({ ...p })),
            fromStation: a.name,
            toStation: b.name,
            distance: d,
            duration: d / RAIL_SPEED,
          },
          walkLeg(pts[pts.length - 1]!, to),
        ]),
      );
    }
  }

  const best = candidates.reduce((x, y) => (total(y).duration < total(x).duration ? y : x));
  const legs = best.map(round);
  const t = total(legs);
  return {
    schemaVersion: 2,
    status: "ok",
    world: req.world,
    from: { ...from },
    to: { ...to },
    legs,
    distance: Math.round(t.distance * 10) / 10,
    duration: Math.round(t.duration * 10) / 10,
    error: null,
  };
}
