/**
 * Pure mock of the mod's `GET /route` endpoint (schema v1, CLAUDE.md). Validation mirrors
 * the old route v1 request parser: `from`/`to` exactly `<int>,<int>` (optional leading "-", digits
 * only, no spaces), each coordinate within ±30,000,000; `world` optional, matching
 * `[a-z0-9_.-]+:[a-z0-9_./-]+`, default `minecraft:overworld`.
 *
 * Special cases (so the UI can show every state):
 * - world not in the bundled mock squaremap settings -> 404 `world_not_found`
 * - `to` x == 13 -> 200 `no_path`; x == 14 -> 200 `cap_exceeded`; x == 15 -> 503 `not_ready`
 */
import mockSettings from "../public/mock-squaremap/tiles/settings.json";
import { httpStatusFor, type BlockPos, type RoutePayload, type RouteStatus } from "./routeSchema";
import { worldIdToWebName } from "./squaremapSettings";

export const DEFAULT_WORLD = "minecraft:overworld";
export const MAX_COORD = 30_000_000;
const WORLD_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const POINT_RE = /^(-?[0-9]+),(-?[0-9]+)$/;

/** Web names (`minecraft_overworld`, ...) of the worlds in the bundled mock settings.json. */
export const MOCK_WORLD_WEB_NAMES: readonly string[] = mockSettings.worlds.map((w) => w.name);

export interface MockResponse {
  httpStatus: number;
  body: RoutePayload;
}

export interface RawQuery {
  from: string | null;
  to: string | null;
  world: string | null;
}

type ParsedPoint = { x: number; z: number } | string;

function coord(digits: string): number | null {
  const neg = digits.startsWith("-");
  const unsigned = neg ? digits.slice(1) : digits;
  const stripped = unsigned.replace(/^0+(?=.)/, "");
  if (stripped.length > 9) return null;
  const v = Number(stripped) * (neg ? -1 : 1);
  return Math.abs(v) <= MAX_COORD ? v + 0 : null; // + 0 normalises -0
}

function parsePoint(name: string, raw: string | null): ParsedPoint {
  if (raw === null) {
    return `missing '${name}' parameter: expected ${name}=<x>,<z> (integer blocks)`;
  }
  const m = POINT_RE.exec(raw);
  if (!m || m[1] === undefined || m[2] === undefined) {
    return `invalid '${name}' value "${raw}": expected two integers <x>,<z> with no spaces, e.g. ${name}=12,-40`;
  }
  const x = coord(m[1]);
  const z = coord(m[2]);
  if (x === null || z === null) {
    return `'${name}' value "${raw}" out of range: coordinates must be within ±${MAX_COORD}`;
  }
  return { x, z };
}

/** Deterministic fake ground height (feet block Y). */
export function mockGroundY(x: number, z: number): number {
  return 64 + Math.round(6 * Math.sin(x / 29) + 4 * Math.cos(z / 41));
}

/** Small deterministic 32-bit hash of four ints. */
function hash4(a: number, b: number, c: number, d: number): number {
  let h = 0x811c9dc5;
  for (const v of [a, b, c, d]) {
    h ^= v | 0;
    h = Math.imul(h, 0x01000193);
    h ^= h >>> 15;
  }
  return h >>> 0;
}

/** 3D polyline length in blocks. */
export function polylineLength(points: readonly BlockPos[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]!;
    const b = points[i]!;
    total += Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z);
  }
  return total;
}

function withY(x: number, z: number): BlockPos {
  return { x, y: mockGroundY(x, z), z };
}

function clampCoord(v: number): number {
  return Math.max(-MAX_COORD, Math.min(MAX_COORD, v));
}

/** Deterministic polyline from -> to with 2..4 alternating bends. */
export function generateMockPoints(from: BlockPos, to: BlockPos): BlockPos[] {
  const dx = to.x - from.x;
  const dz = to.z - from.z;
  const len = Math.hypot(dx, dz);
  if (len === 0) return [from];
  const h = hash4(from.x, from.z, to.x, to.z);
  const bends = 2 + (h % 3);
  const px = -dz / len;
  const pz = dx / len;
  const amp = Math.max(3, Math.min(len * 0.12, 150));
  const pts: BlockPos[] = [from];
  for (let i = 1; i <= bends; i++) {
    const t = i / (bends + 1);
    const jitter = ((h >>> (i * 3)) & 7) / 7;
    const off = (i % 2 === 1 ? 1 : -1) * amp * (0.5 + 0.5 * jitter);
    const x = clampCoord(Math.round(from.x + dx * t + px * off));
    const z = clampCoord(Math.round(from.z + dz * t + pz * off));
    pts.push(withY(x, z));
  }
  pts.push(to);
  // Drop consecutive duplicates in x/z (keeping the first and the final `to`).
  const out: BlockPos[] = [];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i]!;
    const prev = out[out.length - 1];
    const isLast = i === pts.length - 1;
    if (prev && prev.x === p.x && prev.z === p.z) {
      if (isLast) out[out.length - 1] = p;
      continue;
    }
    out.push(p);
  }
  return out;
}

function payload(
  status: RouteStatus,
  world: string,
  fields: Partial<Omit<RoutePayload, "schemaVersion" | "status" | "world">>,
): MockResponse {
  return {
    httpStatus: httpStatusFor(status),
    body: {
      schemaVersion: 1,
      status,
      world,
      from: fields.from ?? null,
      to: fields.to ?? null,
      points: fields.points ?? [],
      distance: fields.distance ?? 0,
      nodesExpanded: fields.nodesExpanded ?? 0,
      error: fields.error ?? null,
    },
  };
}

export function mockRoute(
  q: RawQuery,
  knownWorldWebNames: readonly string[] = MOCK_WORLD_WEB_NAMES,
): MockResponse {
  let world = DEFAULT_WORLD;
  if (q.world !== null) {
    if (!WORLD_RE.test(q.world)) {
      return payload("invalid_request", DEFAULT_WORLD, {
        error: `invalid 'world' value "${q.world}": expected namespace:path, e.g. minecraft:overworld`,
      });
    }
    world = q.world;
  }
  const a = parsePoint("from", q.from);
  if (typeof a === "string") return payload("invalid_request", world, { error: a });
  const b = parsePoint("to", q.to);
  if (typeof b === "string") return payload("invalid_request", world, { error: b });

  if (!knownWorldWebNames.includes(worldIdToWebName(world))) {
    return payload("world_not_found", world, { error: `world '${world}' is not loaded` });
  }
  if (b.x === 15) {
    return payload("not_ready", world, {
      error: `route network for '${world}' is not ready yet`,
    });
  }

  const from = withY(a.x, a.z);
  const to = withY(b.x, b.z);
  const h = hash4(a.x, a.z, b.x, b.z);
  if (b.x === 13) {
    return payload("no_path", world, {
      from,
      to,
      nodesExpanded: 4000 + (h % 20000),
      error: "no walkable path between the endpoints",
    });
  }
  if (b.x === 14) {
    return payload("cap_exceeded", world, {
      from,
      to,
      nodesExpanded: 2_000_000,
      error: "search limit of 2000000 nodes exceeded before a path was found",
    });
  }

  const points = generateMockPoints(from, to);
  const distance = polylineLength(points);
  return payload("ok", world, {
    from,
    to,
    points,
    distance,
    nodesExpanded: Math.round(distance * 9) + (h % 997) + 1,
  });
}
