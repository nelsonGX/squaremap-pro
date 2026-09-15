/** Feature geometry → Leaflet coordinates and bounds. Pure (no Leaflet import). */
import type { Feature, XZ } from "../api/types";
import { blockCentreLatLng, type LatLngLike } from "../squaremapCrs";

/** Vertices drawn at block centre (x + 0.5, z + 0.5) through squaremap's CRS. */
export function vertexLatLngs(points: readonly XZ[], maxZoom: number): LatLngLike[] {
  return points.map((p) => blockCentreLatLng(p.x, p.z, maxZoom));
}

export interface BlockBounds {
  minX: number;
  minZ: number;
  maxX: number;
  maxZ: number;
}

/** Integer vertex bounds, or null for empty geometry. */
export function blockBounds(points: readonly XZ[]): BlockBounds | null {
  if (points.length === 0) return null;
  let minX = Infinity;
  let minZ = Infinity;
  let maxX = -Infinity;
  let maxZ = -Infinity;
  for (const p of points) {
    if (p.x < minX) minX = p.x;
    if (p.x > maxX) maxX = p.x;
    if (p.z < minZ) minZ = p.z;
    if (p.z > maxZ) maxZ = p.z;
  }
  return { minX, minZ, maxX, maxZ };
}

/**
 * LatLng corners of block-centre bounds: `[southWest, northEast]`. North is -z (positive lat),
 * so south-west = (maxZ, minX) and north-east = (minZ, maxX).
 */
export function boundsToLatLngs(b: BlockBounds, maxZoom: number): [LatLngLike, LatLngLike] {
  return [blockCentreLatLng(b.minX, b.maxZ, maxZoom), blockCentreLatLng(b.maxX, b.minZ, maxZoom)];
}

/** Where to fly for a feature: a single point (stations, degenerate shapes) or bounds. */
export type FlyTarget = { kind: "point"; latLng: LatLngLike } | { kind: "bounds"; bounds: [LatLngLike, LatLngLike] };

export function flyTarget(f: Feature, maxZoom: number): FlyTarget | null {
  const b = blockBounds(f.geometry);
  if (!b) return null;
  if (b.minX === b.maxX && b.minZ === b.maxZ) {
    return { kind: "point", latLng: blockCentreLatLng(b.minX, b.minZ, maxZoom) };
  }
  return { kind: "bounds", bounds: boundsToLatLngs(b, maxZoom) };
}

/** Length of a polyline in blocks (2D, vertex to vertex). */
export function polylineLength(points: readonly XZ[]): number {
  let len = 0;
  for (let i = 1; i < points.length; i++) {
    len += Math.hypot(points[i]!.x - points[i - 1]!.x, points[i]!.z - points[i - 1]!.z);
  }
  return len;
}

/** Area of a closed ring in square blocks (shoelace over vertex coordinates; ring not repeated). */
export function ringArea(points: readonly XZ[]): number {
  let twice = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i]!;
    const b = points[(i + 1) % points.length]!;
    twice += a.x * b.z - b.x * a.z;
  }
  return Math.abs(twice) / 2;
}
