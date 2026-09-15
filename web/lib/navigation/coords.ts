/** Coordinate text parsing / formatting and feature → routing point. Pure. */
import type { Feature, XZ } from "../api/types";

/** Parses "x, z" / "x,z" / "x z" (integers, optional sign) into block coordinates, or null. */
export function parseCoordInput(raw: string): XZ | null {
  const m = /^\s*([+-]?\d+)\s*(?:,\s*|\s+)([+-]?\d+)\s*$/.exec(raw);
  if (!m || m[1] === undefined || m[2] === undefined) return null;
  const x = Number(m[1]);
  const z = Number(m[2]);
  if (!Number.isSafeInteger(x) || !Number.isSafeInteger(z)) return null;
  return { x: x + 0, z: z + 0 };
}

export function formatCoord(p: XZ): string {
  return `${p.x}, ${p.z}`;
}

/** Query-string form `x,z`. */
export function coordParam(p: XZ): string {
  return `${p.x},${p.z}`;
}

/**
 * Area centroid of a ring (shoelace), floored to a block. Falls back to the vertex average for
 * degenerate (zero-area) rings. For concave shapes the centroid can lie outside the outline.
 */
export function ringCentroidBlock(ring: readonly XZ[]): XZ | null {
  if (ring.length === 0) return null;
  let a2 = 0;
  let cx = 0;
  let cz = 0;
  // Use block centres so a 1x1 building's centroid is its own block.
  for (let i = 0; i < ring.length; i++) {
    const p = ring[i]!;
    const q = ring[(i + 1) % ring.length]!;
    const px = p.x + 0.5;
    const pz = p.z + 0.5;
    const qx = q.x + 0.5;
    const qz = q.z + 0.5;
    const c = px * qz - qx * pz;
    a2 += c;
    cx += (px + qx) * c;
    cz += (pz + qz) * c;
  }
  if (a2 === 0) {
    const sx = ring.reduce((s, p) => s + p.x + 0.5, 0) / ring.length;
    const sz = ring.reduce((s, p) => s + p.z + 0.5, 0) / ring.length;
    return { x: Math.floor(sx) + 0, z: Math.floor(sz) + 0 };
  }
  return { x: Math.floor(cx / (3 * a2)) + 0, z: Math.floor(cz / (3 * a2)) + 0 };
}

function nearestOf(points: readonly XZ[], near: XZ): XZ {
  let best = points[0]!;
  let bestD = Infinity;
  for (const p of points) {
    const d = Math.hypot(p.x - near.x, p.z - near.z);
    if (d < bestD) {
      best = p;
      bestD = d;
    }
  }
  return best;
}

/**
 * Routing point for a feature: building → area centroid; station → its point; road/railway → the
 * vertex nearest `near` when given, else the middle vertex.
 */
export function featureAnchor(f: Feature, near?: XZ | null): XZ | null {
  if (f.geometry.length === 0) return null;
  switch (f.type) {
    case "building":
      return ringCentroidBlock(f.geometry);
    case "station":
      return { ...f.geometry[0]! };
    case "road":
    case "railway": {
      const p = near ? nearestOf(f.geometry, near) : f.geometry[Math.floor((f.geometry.length - 1) / 2)]!;
      return { x: p.x, z: p.z };
    }
  }
}
