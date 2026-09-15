/**
 * Hand-written runtime validation for API responses (no schema library). Each `parseX` returns
 * either the typed value (copied, with only known keys) or a short problem description.
 * Unknown extra keys are tolerated everywhere (forward compatibility).
 */
import {
  BUILDING_CATEGORIES,
  FEATURE_TYPES,
  ROAD_CLASSES,
  ROUTE_MODES,
  ROUTE_STATUSES,
  type ApiErrorBody,
  type AuthMe,
  type Feature,
  type FeatureList,
  type PlayerRef,
  type RouteLeg,
  type RouteResponse,
  type ValidationDetail,
  type World,
  type XZ,
} from "./types";

export type Parsed<T> = { ok: true; value: T } | { ok: false; problem: string };

const ok = <T>(value: T): Parsed<T> => ({ ok: true, value });
const fail = <T>(problem: string): Parsed<T> => ({ ok: false, problem });

export function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

const isInt = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v);
const isFiniteNonNeg = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v) && v >= 0;
const isStr = (v: unknown): v is string => typeof v === "string";
const oneOf = <T extends string>(values: readonly T[], v: unknown): v is T =>
  typeof v === "string" && (values as readonly string[]).includes(v);

export const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;

export function isXZ(v: unknown): v is XZ {
  return isObj(v) && isInt(v.x) && isInt(v.z);
}

const xz = (v: XZ): XZ => ({ x: v.x + 0, z: v.z + 0 });

export function parseWorlds(v: unknown): Parsed<World[]> {
  if (!Array.isArray(v)) return fail("worlds is not an array");
  const out: World[] = [];
  for (const [i, w] of v.entries()) {
    if (!isObj(w) || !isStr(w.id) || w.id === "" || !isStr(w.name)) return fail(`worlds[${i}] is not {id, name}`);
    out.push({ id: w.id, name: w.name });
  }
  return ok(out);
}

export function parseAuthMe(v: unknown): Parsed<AuthMe> {
  if (!isObj(v) || typeof v.loggedIn !== "boolean") return fail("auth/me has no boolean loggedIn");
  if (!v.loggedIn) return ok({ loggedIn: false });
  if (!isStr(v.uuid) || !isStr(v.name) || typeof v.canEdit !== "boolean") {
    return fail("auth/me loggedIn without uuid/name/canEdit");
  }
  return ok({ loggedIn: true, uuid: v.uuid, name: v.name, canEdit: v.canEdit });
}

function parsePlayer(v: unknown): PlayerRef | null {
  return isObj(v) && isStr(v.uuid) && isStr(v.name) ? { uuid: v.uuid, name: v.name } : null;
}

/**
 * Validates one feature against the Features v1 table. Geometry vertex counts are checked
 * (building >= 3 and first != last, road/railway >= 2, station exactly 1); deeper rules (simple
 * rings, station on its railway) are the server's job.
 */
export function parseFeature(v: unknown): Parsed<Feature> {
  if (!isObj(v)) return fail("feature is not an object");
  if (!isStr(v.id) || v.id === "") return fail("id is not a non-empty string");
  const where = `feature ${v.id}`;
  if (!oneOf(FEATURE_TYPES, v.type)) return fail(`${where}: unknown type`);
  if (!isInt(v.revision) || v.revision < 0) return fail(`${where}: revision is not a non-negative integer`);
  if (!isStr(v.name)) return fail(`${where}: name is not a string`);
  if (!Array.isArray(v.geometry) || !v.geometry.every(isXZ)) return fail(`${where}: geometry is not {x,z}[]`);
  const geometry = (v.geometry as XZ[]).map(xz);
  const createdBy = parsePlayer(v.createdBy);
  const updatedBy = parsePlayer(v.updatedBy);
  if (!createdBy || !updatedBy) return fail(`${where}: createdBy/updatedBy is not {uuid, name}`);
  if (!isStr(v.createdAt) || !isStr(v.updatedAt)) return fail(`${where}: createdAt/updatedAt is not a string`);
  if (!isObj(v.props)) return fail(`${where}: props is not an object`);
  const p = v.props;
  const base = {
    id: v.id,
    revision: v.revision,
    name: v.name,
    geometry,
    createdBy,
    createdAt: v.createdAt,
    updatedBy,
    updatedAt: v.updatedAt,
  };
  const n = geometry.length;
  switch (v.type) {
    case "building": {
      const first = geometry[0];
      const last = geometry[n - 1];
      if (n < 3 || !first || !last) return fail(`${where}: building needs >= 3 vertices`);
      if (first.x === last.x && first.z === last.z) return fail(`${where}: building ring must not repeat the first vertex`);
      if (!oneOf(BUILDING_CATEGORIES, p.category)) return fail(`${where}: unknown building category`);
      // description: tolerate a missing / null value as "".
      if (p.description !== undefined && p.description !== null && !isStr(p.description)) {
        return fail(`${where}: description is not a string`);
      }
      return ok({ ...base, type: "building", props: { category: p.category, description: p.description ?? "" } });
    }
    case "road":
      if (n < 2) return fail(`${where}: road needs >= 2 vertices`);
      if (!oneOf(ROAD_CLASSES, p.roadClass)) return fail(`${where}: unknown roadClass`);
      return ok({ ...base, type: "road", props: { roadClass: p.roadClass } });
    case "railway":
      if (n < 2) return fail(`${where}: railway needs >= 2 vertices`);
      if (!isStr(p.colour) || !HEX_COLOUR.test(p.colour)) return fail(`${where}: colour is not #rrggbb`);
      return ok({ ...base, type: "railway", props: { colour: p.colour.toLowerCase() } });
    case "station":
      if (n !== 1) return fail(`${where}: station needs exactly 1 vertex`);
      if (!isStr(p.railwayId) || p.railwayId === "") return fail(`${where}: railwayId is not a non-empty string`);
      return ok({ ...base, type: "station", props: { railwayId: p.railwayId } });
  }
}

export interface ParsedFeatureList extends FeatureList {
  /** Entries that failed validation and were left out (so one bad row does not blank the map). */
  skipped: { index: number; problem: string }[];
}

export function parseFeatureList(v: unknown): Parsed<ParsedFeatureList> {
  if (!isObj(v)) return fail("feature list is not an object");
  if (v.schemaVersion !== 1) return fail("feature list schemaVersion is not 1");
  if (!Array.isArray(v.features)) return fail("features is not an array");
  const features: Feature[] = [];
  const skipped: ParsedFeatureList["skipped"] = [];
  const seen = new Set<string>();
  v.features.forEach((raw, index) => {
    const r = parseFeature(raw);
    if (!r.ok) skipped.push({ index, problem: r.problem });
    else if (seen.has(r.value.id)) skipped.push({ index, problem: `duplicate id ${r.value.id}` });
    else {
      seen.add(r.value.id);
      features.push(r.value);
    }
  });
  return ok({ schemaVersion: 1, features, skipped });
}

/** Lenient: returns null when the body is not an `{error, details?}` object. */
export function parseApiErrorBody(v: unknown): ApiErrorBody | null {
  if (!isObj(v) || !isStr(v.error)) return null;
  const details: ValidationDetail[] = [];
  if (Array.isArray(v.details)) {
    for (const d of v.details) {
      if (isObj(d) && isStr(d.field) && isStr(d.message)) details.push({ field: d.field, message: d.message });
    }
  }
  return { error: v.error, details };
}

function sameXZ(a: XZ, b: XZ): boolean {
  return a.x === b.x && a.z === b.z;
}

const ROUTE_KEYS = ["schemaVersion", "status", "world", "from", "to", "legs", "distance", "duration", "error"] as const;

function parseLeg(v: unknown, i: number): Parsed<RouteLeg> {
  const where = `legs[${i}]`;
  if (!isObj(v)) return fail(`${where} is not an object`);
  if (!oneOf(ROUTE_MODES, v.mode)) return fail(`${where}: unknown mode`);
  if (v.name !== null && !isStr(v.name)) return fail(`${where}: name is not a string or null`);
  if (!Array.isArray(v.points) || v.points.length < 2 || !v.points.every(isXZ)) {
    return fail(`${where}: points is not {x,z}[] with >= 2 entries`);
  }
  if (!isFiniteNonNeg(v.distance) || !isFiniteNonNeg(v.duration)) return fail(`${where}: distance/duration invalid`);
  const leg: RouteLeg = {
    mode: v.mode,
    name: v.name,
    points: (v.points as XZ[]).map(xz),
    distance: v.distance,
    duration: v.duration,
  };
  if (v.mode === "rail") {
    if (!isStr(v.fromStation) || !isStr(v.toStation)) return fail(`${where}: rail leg without fromStation/toStation`);
    leg.fromStation = v.fromStation;
    leg.toStation = v.toStation;
  }
  return ok(leg);
}

/**
 * Route schema v2. Rules: all keys present; schemaVersion 2; known status; from/to `{x,z}` or null;
 * legs valid; distance/duration finite >= 0; error string or null. `ok`: from/to non-null, >= 1 leg,
 * first leg starts at `from`, last leg ends at `to`, legs consecutive, error null. Otherwise: no legs,
 * distance = duration = 0.
 */
export function parseRouteResponse(v: unknown): Parsed<RouteResponse> {
  if (!isObj(v)) return fail("route is not an object");
  for (const k of ROUTE_KEYS) if (!(k in v)) return fail(`missing key '${k}'`);
  if (v.schemaVersion !== 2) return fail("schemaVersion is not 2");
  if (!oneOf(ROUTE_STATUSES, v.status)) return fail("unknown status");
  if (!isStr(v.world)) return fail("world is not a string");
  if (v.from !== null && !isXZ(v.from)) return fail("from is not {x,z} or null");
  if (v.to !== null && !isXZ(v.to)) return fail("to is not {x,z} or null");
  if (!Array.isArray(v.legs)) return fail("legs is not an array");
  if (!isFiniteNonNeg(v.distance) || !isFiniteNonNeg(v.duration)) return fail("distance/duration invalid");
  if (v.error !== null && !isStr(v.error)) return fail("error is not a string or null");
  const legs: RouteLeg[] = [];
  for (const [i, raw] of v.legs.entries()) {
    const r = parseLeg(raw, i);
    if (!r.ok) return fail(r.problem);
    legs.push(r.value);
  }
  const from = v.from === null ? null : xz(v.from);
  const to = v.to === null ? null : xz(v.to);
  if (v.status === "ok") {
    if (!from || !to) return fail("ok route without from/to");
    if (legs.length === 0) return fail("ok route without legs");
    if (v.error !== null) return fail("ok route with error");
    if (!sameXZ(legs[0]!.points[0]!, from)) return fail("first leg does not start at from");
    const lastLeg = legs[legs.length - 1]!;
    if (!sameXZ(lastLeg.points[lastLeg.points.length - 1]!, to)) return fail("last leg does not end at to");
    for (let i = 1; i < legs.length; i++) {
      const prev = legs[i - 1]!.points;
      if (!sameXZ(prev[prev.length - 1]!, legs[i]!.points[0]!)) return fail(`legs[${i}] does not start where legs[${i - 1}] ends`);
    }
  } else {
    if (legs.length !== 0) return fail(`legs present on '${v.status}'`);
    if (v.distance !== 0 || v.duration !== 0) return fail(`non-zero distance/duration on '${v.status}'`);
  }
  return ok({
    schemaVersion: 2,
    status: v.status,
    world: v.world,
    from,
    to,
    legs,
    distance: v.distance,
    duration: v.duration,
    error: v.error,
  });
}
