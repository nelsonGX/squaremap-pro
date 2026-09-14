/**
 * Route JSON schema v1 (CLAUDE.md "Route JSON schema (v1)"). Hand-written type guard, no runtime
 * dependency.
 */

export const ROUTE_STATUSES = [
  "ok",
  "no_path",
  "cap_exceeded",
  "invalid_request",
  "world_not_found",
  "not_ready",
] as const;

export type RouteStatus = (typeof ROUTE_STATUSES)[number];

export interface BlockPos {
  x: number;
  y: number;
  z: number;
}

export interface RoutePayload {
  schemaVersion: 1;
  status: RouteStatus;
  world: string;
  from: BlockPos | null;
  to: BlockPos | null;
  points: BlockPos[];
  distance: number;
  nodesExpanded: number;
  error: string | null;
}

/** HTTP status per schema v1. */
export function httpStatusFor(status: RouteStatus): number {
  switch (status) {
    case "ok":
    case "no_path":
    case "cap_exceeded":
      return 200;
    case "invalid_request":
      return 400;
    case "world_not_found":
      return 404;
    case "not_ready":
      return 503;
  }
}

const KEYS = [
  "schemaVersion",
  "status",
  "world",
  "from",
  "to",
  "points",
  "distance",
  "nodesExpanded",
  "error",
] as const;

function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function isInt(v: unknown): v is number {
  return typeof v === "number" && Number.isInteger(v);
}

export function isBlockPos(v: unknown): v is BlockPos {
  return isObj(v) && isInt(v.x) && isInt(v.y) && isInt(v.z);
}

function samePos(a: BlockPos, b: BlockPos): boolean {
  return a.x === b.x && a.y === b.y && a.z === b.z;
}

export function isRouteStatus(v: unknown): v is RouteStatus {
  return typeof v === "string" && (ROUTE_STATUSES as readonly string[]).includes(v);
}

/**
 * Returns null if `v` is a valid schema v1 payload, else a short reason. Rules:
 * all nine keys present; `schemaVersion === 1`; known `status`; `from`/`to` are integer `{x,y,z}`
 * or null; `points` integer `{x,y,z}[]`; `distance` finite >= 0; `nodesExpanded` integer >= 0;
 * `error` string or null. For `ok`: from/to non-null, points non-empty with first = from and
 * last = to, `error === null`. Otherwise: `points` empty and `distance === 0`.
 * Unknown extra keys are tolerated.
 */
export function routePayloadProblem(v: unknown): string | null {
  if (!isObj(v)) return "payload is not an object";
  for (const k of KEYS) {
    if (!(k in v)) return `missing key '${k}'`;
  }
  if (v.schemaVersion !== 1) return "schemaVersion is not 1";
  if (!isRouteStatus(v.status)) return "unknown status";
  if (typeof v.world !== "string") return "world is not a string";
  if (v.from !== null && !isBlockPos(v.from)) return "from is not {x,y,z} or null";
  if (v.to !== null && !isBlockPos(v.to)) return "to is not {x,y,z} or null";
  if (!Array.isArray(v.points) || !v.points.every(isBlockPos)) return "points is not {x,y,z}[]";
  if (typeof v.distance !== "number" || !Number.isFinite(v.distance) || v.distance < 0) {
    return "distance is not a finite non-negative number";
  }
  if (!isInt(v.nodesExpanded) || v.nodesExpanded < 0) return "nodesExpanded is not a non-negative integer";
  if (v.error !== null && typeof v.error !== "string") return "error is not a string or null";

  const points = v.points as BlockPos[];
  if (v.status === "ok") {
    if (v.from === null || v.to === null) return "ok payload without from/to";
    const first = points[0];
    const last = points[points.length - 1];
    if (first === undefined || last === undefined) return "ok payload without points";
    if (!samePos(first, v.from as BlockPos)) return "first point is not from";
    if (!samePos(last, v.to as BlockPos)) return "last point is not to";
    if (v.error !== null) return "ok payload with error";
  } else {
    if (points.length !== 0) return `points present on '${v.status}'`;
    if (v.distance !== 0) return `non-zero distance on '${v.status}'`;
  }
  return null;
}

export function isRoutePayload(v: unknown): v is RoutePayload {
  return routePayloadProblem(v) === null;
}
