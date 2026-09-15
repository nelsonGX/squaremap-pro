/**
 * Client-side mirror of map-core `FeatureValidator` for immediate feedback. The server's answer is
 * authoritative; these rules are copied from it (limits, messages' meaning), not invented:
 * name 0..64 code points for buildings and 1..64 otherwise (trimmed), description <= 1000,
 * colour `#rrggbb`, vertex counts (>= 2 lines, >= 3 buildings, <= 2000), |coord| <= 30,000,000,
 * no identical consecutive vertices, open ring, >= 3 distinct, simple ring (integer segment tests),
 * non-zero area, station on a vertex of an existing railway.
 */
import type { FeatureInput, ValidationDetail, XZ } from "../api/types";
import { HEX_COLOUR } from "../api/guards";

export const MAX_NAME_LENGTH = 64;
export const MAX_DESCRIPTION_LENGTH = 1000;
export const MAX_COORDINATE = 30_000_000;
export const MAX_VERTICES = 2000;

/** Code point length, as Java `codePointCount`. */
export function codePointLength(s: string): number {
  return Array.from(s).length;
}

/** Returns the railway's vertices, or undefined if no such railway exists. */
export type RailwayLookup = (railwayId: string) => readonly XZ[] | undefined;

const cross = (o: XZ, b: XZ, c: XZ) => (b.x - o.x) * (c.z - o.z) - (b.z - o.z) * (c.x - o.x);
const dot = (o: XZ, b: XZ, c: XZ) => (b.x - o.x) * (c.x - o.x) + (b.z - o.z) * (c.z - o.z);
const eq = (a: XZ, b: XZ) => a.x === b.x && a.z === b.z;

function onSegment(p: XZ, q: XZ, r: XZ): boolean {
  return (
    Math.min(p.x, q.x) <= r.x && r.x <= Math.max(p.x, q.x) && Math.min(p.z, q.z) <= r.z && r.z <= Math.max(p.z, q.z)
  );
}

/** Closed-segment intersection including touching and collinear overlap. */
export function segmentsIntersect(p1: XZ, p2: XZ, p3: XZ, p4: XZ): boolean {
  const d1 = Math.sign(cross(p3, p4, p1));
  const d2 = Math.sign(cross(p3, p4, p2));
  const d3 = Math.sign(cross(p1, p2, p3));
  const d4 = Math.sign(cross(p1, p2, p4));
  if (d1 * d2 < 0 && d3 * d4 < 0) return true;
  return (
    (d1 === 0 && onSegment(p3, p4, p1)) ||
    (d2 === 0 && onSegment(p3, p4, p2)) ||
    (d3 === 0 && onSegment(p1, p2, p3)) ||
    (d4 === 0 && onSegment(p1, p2, p4))
  );
}

/**
 * Simplicity check for a ring without zero-length edges (edge i = vertex i → (i+1) % n). Adjacent
 * edges may only share their common vertex; non-adjacent edges must not touch. Coordinates up to
 * 3e7 keep every cross product (<= 3.6e15) exact in a double.
 */
export function findSelfIntersection(ring: readonly XZ[]): string | null {
  const n = ring.length;
  for (let i = 0; i < n; i++) {
    const a1 = ring[i]!;
    const a2 = ring[(i + 1) % n]!;
    for (let j = i + 1; j < n; j++) {
      const b1 = ring[j]!;
      const b2 = ring[(j + 1) % n]!;
      const adjacent = j === i + 1 || (i === 0 && j === n - 1);
      if (adjacent) {
        const s = j === i + 1 ? a2 : a1;
        const p = j === i + 1 ? a1 : a2;
        const q = j === i + 1 ? b2 : b1;
        if (cross(s, p, q) === 0 && dot(s, p, q) > 0) return `edges ${i} and ${j} overlap`;
      } else if (segmentsIntersect(a1, a2, b1, b2)) {
        return `edges ${i} and ${j} intersect`;
      }
    }
  }
  return null;
}

function twiceSignedArea(ring: readonly XZ[]): number {
  const o = ring[0]!;
  let sum = 0;
  for (let i = 1; i + 1 < ring.length; i++) sum += cross(o, ring[i]!, ring[i + 1]!);
  return sum;
}

function checkName(name: string, min: number, out: ValidationDetail[]): void {
  const len = codePointLength(name.trim());
  if (len < min) out.push({ field: "name", message: "Name is required" });
  else if (len > MAX_NAME_LENGTH) out.push({ field: "name", message: `Name must be at most ${MAX_NAME_LENGTH} characters` });
}

function checkVertices(v: readonly XZ[], min: number, what: string, out: ValidationDetail[]): boolean {
  if (v.length < min) {
    out.push({ field: "geometry", message: `A ${what} needs at least ${min} points` });
    return false;
  }
  if (v.length > MAX_VERTICES) {
    out.push({ field: "geometry", message: `A ${what} can have at most ${MAX_VERTICES} points` });
    return false;
  }
  const bad = v.findIndex((p) => Math.abs(p.x) > MAX_COORDINATE || Math.abs(p.z) > MAX_COORDINATE);
  if (bad >= 0) {
    out.push({ field: "geometry", message: `Point ${bad} is outside ±${MAX_COORDINATE}` });
    return false;
  }
  return true;
}

function checkPolyline(line: readonly XZ[], out: ValidationDetail[]): void {
  if (!checkVertices(line, 2, "line", out)) return;
  for (let i = 1; i < line.length; i++) {
    if (eq(line[i]!, line[i - 1]!)) {
      out.push({ field: "geometry", message: `Points ${i - 1} and ${i} are identical` });
      return;
    }
  }
}

function checkRing(ring: readonly XZ[], out: ValidationDetail[]): void {
  if (!checkVertices(ring, 3, "building", out)) return;
  const n = ring.length;
  if (eq(ring[0]!, ring[n - 1]!)) {
    out.push({ field: "geometry", message: "The outline must not repeat its first point" });
    return;
  }
  for (let i = 1; i < n; i++) {
    if (eq(ring[i]!, ring[i - 1]!)) {
      out.push({ field: "geometry", message: `Points ${i - 1} and ${i} are identical` });
      return;
    }
  }
  if (new Set(ring.map((p) => `${p.x},${p.z}`)).size < 3) {
    out.push({ field: "geometry", message: "A building needs at least 3 distinct points" });
    return;
  }
  const si = findSelfIntersection(ring);
  if (si) {
    out.push({ field: "geometry", message: `The outline crosses itself (${si})` });
    return;
  }
  if (twiceSignedArea(ring) === 0) out.push({ field: "geometry", message: "The building has zero area" });
}

export function validateInput(input: FeatureInput, railways: RailwayLookup): ValidationDetail[] {
  const out: ValidationDetail[] = [];
  switch (input.type) {
    case "building":
      checkName(input.name, 0, out);
      if (codePointLength(input.props.description) > MAX_DESCRIPTION_LENGTH) {
        out.push({ field: "description", message: `Description must be at most ${MAX_DESCRIPTION_LENGTH} characters` });
      }
      checkRing(input.geometry, out);
      break;
    case "road":
      checkName(input.name, 1, out);
      checkPolyline(input.geometry, out);
      break;
    case "railway":
      checkName(input.name, 1, out);
      if (!HEX_COLOUR.test(input.props.colour)) out.push({ field: "colour", message: "Colour must be #rrggbb" });
      checkPolyline(input.geometry, out);
      break;
    case "station": {
      checkName(input.name, 1, out);
      const point = input.geometry.length === 1 ? input.geometry[0]! : null;
      if (!point) out.push({ field: "geometry", message: "Place the station on a railway point" });
      if (input.props.railwayId === "") {
        if (point) out.push({ field: "railwayId", message: "Place the station on a railway point" });
        break;
      }
      const line = railways(input.props.railwayId);
      if (!line) out.push({ field: "railwayId", message: "The railway does not exist" });
      else if (point && !line.some((v) => eq(v, point))) {
        out.push({ field: "geometry", message: "The station must be on a point of its railway" });
      }
      break;
    }
  }
  return out;
}

/** Details grouped by field; unknown fields go to "" (shown at the top of the form). */
export function detailsByField(details: readonly ValidationDetail[], knownFields: readonly string[]): Map<string, string[]> {
  const m = new Map<string, string[]>();
  for (const d of details) {
    const key = knownFields.includes(d.field) ? d.field : "";
    m.set(key, [...(m.get(key) ?? []), d.message]);
  }
  return m;
}
