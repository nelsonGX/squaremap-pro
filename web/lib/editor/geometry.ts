/**
 * Editor geometry: rounding to integer blocks, vertex snapping and geometry clean-up. Pure.
 *
 * Positions coming from the map are fractional block coordinates (squaremap `toPoint`). A vertex is
 * stored as the block containing the position (`floor`, like squaremap's `UICoordinates`) and drawn
 * at the block centre (x + 0.5, z + 0.5).
 */
import type { Feature, FeatureType, XZ } from "../api/types";

/** Snap radius in screen pixels (also passed to leaflet-geoman as `snapDistance`). */
export const SNAP_DISTANCE_PX = 14;
/** Station placement radius in screen pixels. */
export const STATION_SNAP_DISTANCE_PX = 18;

/** Blocks per screen pixel at map zoom `zoom` (one block = one pixel at `zoom = maxZoom`). */
export function blocksPerPixel(zoom: number, maxZoom: number): number {
  return Math.pow(2, maxZoom - zoom);
}

/** Block containing a fractional position. */
export function toBlockXZ(pos: { x: number; z: number }): XZ {
  return { x: Math.floor(pos.x) + 0, z: Math.floor(pos.z) + 0 };
}

function centreDistance(pos: { x: number; z: number }, v: XZ): number {
  return Math.hypot(pos.x - (v.x + 0.5), pos.z - (v.z + 0.5));
}

/** Nearest target (by distance to its block centre) within `maxDistance` blocks, or null. */
export function nearestVertex(pos: { x: number; z: number }, targets: readonly XZ[], maxDistance: number): XZ | null {
  let best: XZ | null = null;
  let bestD = Infinity;
  for (const t of targets) {
    const d = centreDistance(pos, t);
    if (d <= maxDistance && d < bestD) {
      best = t;
      bestD = d;
    }
  }
  return best;
}

/** Snap to the nearest target within `maxDistance` blocks, else round down to the containing block. */
export function snapPosition(pos: { x: number; z: number }, targets: readonly XZ[], maxDistance: number): XZ {
  const hit = nearestVertex(pos, targets, maxDistance);
  return hit ? { x: hit.x, z: hit.z } : toBlockXZ(pos);
}

/** Types whose vertices snap to other features' vertices (shared vertices = junctions). */
export const SNAPPING_TYPES: readonly FeatureType[] = ["road", "railway"];

/** Distinct vertices of roads and railways, excluding the feature being edited. */
export function snapTargets(features: readonly Feature[], excludeId: string | null): XZ[] {
  const seen = new Set<string>();
  const out: XZ[] = [];
  for (const f of features) {
    if (f.id === excludeId || !SNAPPING_TYPES.includes(f.type)) continue;
    for (const v of f.geometry) {
      const k = `${v.x},${v.z}`;
      if (!seen.has(k)) {
        seen.add(k);
        out.push(v);
      }
    }
  }
  return out;
}

/**
 * Removes consecutive duplicate vertices (which rounding can create). For buildings the ring is
 * open, so a last vertex equal to the first is dropped too.
 */
export function cleanGeometry(type: FeatureType, points: readonly XZ[]): XZ[] {
  const out: XZ[] = [];
  for (const p of points) {
    const last = out[out.length - 1];
    if (!last || last.x !== p.x || last.z !== p.z) out.push({ x: p.x, z: p.z });
  }
  if (type === "building") {
    while (out.length > 1 && out[0]!.x === out[out.length - 1]!.x && out[0]!.z === out[out.length - 1]!.z) out.pop();
  }
  if (type === "station") return out.slice(0, 1);
  return out;
}

/**
 * Map positions → stored geometry: road/railway vertices snap to other road/railway vertices,
 * everything is rounded to blocks and cleaned.
 */
export function positionsToGeometry(
  type: FeatureType,
  positions: readonly { x: number; z: number }[],
  targets: readonly XZ[],
  maxDistanceBlocks: number,
): XZ[] {
  const snap = SNAPPING_TYPES.includes(type);
  return cleanGeometry(
    type,
    positions.map((p) => (snap ? snapPosition(p, targets, maxDistanceBlocks) : toBlockXZ(p))),
  );
}

export interface StationPlacement {
  vertex: XZ;
  /** Railways that have this vertex (usually one; several at a shared vertex), in feature order. */
  railwayIds: string[];
}

/** Nearest railway vertex within `maxDistance` blocks, with every railway sharing that vertex. */
export function placeStation(
  pos: { x: number; z: number },
  features: readonly Feature[],
  maxDistance: number,
): StationPlacement | null {
  const railways = features.filter((f) => f.type === "railway");
  const vertex = nearestVertex(
    pos,
    railways.flatMap((r) => r.geometry),
    maxDistance,
  );
  if (!vertex) return null;
  return {
    vertex: { x: vertex.x, z: vertex.z },
    railwayIds: railways.filter((r) => r.geometry.some((v) => v.x === vertex.x && v.z === vertex.z)).map((r) => r.id),
  };
}

/** Railways (ids) having a vertex exactly at `v`. */
export function railwaysAtVertex(v: XZ, features: readonly Feature[]): string[] {
  return features
    .filter((f) => f.type === "railway" && f.geometry.some((g) => g.x === v.x && g.z === v.z))
    .map((f) => f.id);
}

export function sameGeometry(a: readonly XZ[], b: readonly XZ[]): boolean {
  return a.length === b.length && a.every((p, i) => p.x === b[i]!.x && p.z === b[i]!.z);
}
