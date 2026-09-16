/**
 * Map labels: which names get drawn on the map, where, and which ones lose a collision.
 *
 * Until now a feature's name existed only in a hover tooltip, so the map itself said nothing — you
 * could not tell which line was which, where a station was, or what any footprint was without
 * pointing at it. This is the text layer that a general-purpose map lives or dies by.
 *
 * Pure: candidates are plain data in world-block coordinates, and placement works on already
 * projected screen boxes, so both halves are unit-testable without Leaflet or a DOM.
 */
import type { Feature, XZ } from "../api/types";
import type { MapDetail } from "./detail";
import type { Interchange } from "./interchange";

export type LabelKind = "interchange" | "station" | "railway" | "road" | "building";

export interface LabelCandidate {
  /** Unique per label (feature id, or the interchange's id). */
  id: string;
  /** Feature to select when the label is clicked. */
  featureId: string;
  kind: LabelKind;
  text: string;
  /** Where the label hangs, in world blocks. */
  anchor: XZ;
  /** Higher wins a collision. */
  priority: number;
  /** Railway/station tint; undefined for roads and buildings. */
  colour?: string;
}

/** Base priority per kind: transit first, then the road hierarchy, then footprints. */
const KIND_PRIORITY: Record<LabelKind, number> = {
  interchange: 600,
  station: 500,
  railway: 400,
  road: 200,
  building: 100,
};

const ROAD_CLASS_BONUS = { highway: 90, main: 60, street: 30, path: 0 } as const;

/**
 * Point halfway along a polyline *by length* (not by vertex count), so a line whose vertices bunch
 * up at one end is still labelled in the middle of the drawn line.
 */
export function polylineMidpoint(points: readonly XZ[]): XZ | null {
  if (points.length === 0) return null;
  if (points.length === 1) return points[0]!;
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += Math.hypot(points[i]!.x - points[i - 1]!.x, points[i]!.z - points[i - 1]!.z);
  }
  let walked = 0;
  const half = total / 2;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]!;
    const b = points[i]!;
    const seg = Math.hypot(b.x - a.x, b.z - a.z);
    if (walked + seg >= half && seg > 0) {
      const t = (half - walked) / seg;
      return { x: a.x + (b.x - a.x) * t, z: a.z + (b.z - a.z) * t };
    }
    walked += seg;
  }
  return points[points.length - 1]!;
}

/**
 * Centroid of a closed ring (area-weighted, shoelace). Falls back to the vertex mean for a
 * degenerate ring, so a zero-area footprint still gets a label position.
 */
export function ringCentroid(points: readonly XZ[]): XZ | null {
  if (points.length === 0) return null;
  let twice = 0;
  let cx = 0;
  let cz = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i]!;
    const b = points[(i + 1) % points.length]!;
    const cross = a.x * b.z - b.x * a.z;
    twice += cross;
    cx += (a.x + b.x) * cross;
    cz += (a.z + b.z) * cross;
  }
  if (twice === 0) {
    const n = points.length;
    return { x: points.reduce((s, p) => s + p.x, 0) / n, z: points.reduce((s, p) => s + p.z, 0) / n };
  }
  return { x: cx / (3 * twice), z: cz / (3 * twice) };
}

/** Size in blocks, used to suppress labels for things too small to matter at this zoom. */
function extent(points: readonly XZ[]): number {
  let minX = Infinity;
  let minZ = Infinity;
  let maxX = -Infinity;
  let maxZ = -Infinity;
  for (const p of points) {
    minX = Math.min(minX, p.x);
    maxX = Math.max(maxX, p.x);
    minZ = Math.min(minZ, p.z);
    maxZ = Math.max(maxZ, p.z);
  }
  return Math.max(maxX - minX, maxZ - minZ);
}

/** 0..9, so the longer of two same-class lines wins a collision. */
function lengthBonus(points: readonly XZ[]): number {
  return Math.min(9, Math.round(extent(points) / 200));
}

/** Unnamed features get no label — "Unnamed road" printed on the map is noise, not information. */
function named(f: Feature): string | null {
  const n = f.name.trim();
  return n === "" ? null : n;
}

/**
 * Labels worth drawing at this level of detail, before collision resolution.
 *
 * `interchanges` are the groups the map draws as a single marker; their members are labelled once,
 * under one name, instead of as a pile of overlapping station names.
 */
export function labelCandidates(
  features: readonly Feature[],
  interchanges: readonly Interchange[],
  detail: MapDetail,
  railwayColours: ReadonlyMap<string, string>,
): LabelCandidate[] {
  const out: LabelCandidate[] = [];
  const merged = new Set<string>();
  for (const g of interchanges) {
    if (g.stations.length < 2) continue;
    for (const s of g.stations) merged.add(s.id);
    if (!detail.labels.station) continue;
    const names = [...new Set(g.stations.map((s) => s.name.trim()).filter((n) => n !== ""))];
    if (names.length === 0) continue;
    out.push({
      id: g.id,
      featureId: g.stations[0]!.id,
      kind: "interchange",
      text: names[0]!,
      anchor: g.point,
      priority: KIND_PRIORITY.interchange + g.stations.length,
      colour: railwayColours.get(g.stations[0]!.props.railwayId),
    });
  }
  for (const f of features) {
    const text = named(f);
    if (text === null) continue;
    switch (f.type) {
      case "station": {
        if (!detail.labels.station || merged.has(f.id)) continue;
        const p = f.geometry[0];
        if (!p) continue;
        out.push({
          id: f.id,
          featureId: f.id,
          kind: "station",
          text,
          anchor: p,
          priority: KIND_PRIORITY.station,
          colour: railwayColours.get(f.props.railwayId),
        });
        continue;
      }
      case "railway": {
        if (!detail.labels.railway) continue;
        const p = polylineMidpoint(f.geometry);
        if (!p) continue;
        out.push({
          id: f.id,
          featureId: f.id,
          kind: "railway",
          text,
          anchor: p,
          priority: KIND_PRIORITY.railway + lengthBonus(f.geometry),
          colour: f.props.colour,
        });
        continue;
      }
      case "road": {
        if (!detail.labels.road) continue;
        // A road drawn shorter than its own label is not worth naming at this zoom.
        if (extent(f.geometry) / detail.blocksPerPixel < 40) continue;
        const p = polylineMidpoint(f.geometry);
        if (!p) continue;
        out.push({
          id: f.id,
          featureId: f.id,
          kind: "road",
          text,
          anchor: p,
          priority: KIND_PRIORITY.road + ROAD_CLASS_BONUS[f.props.roadClass] + lengthBonus(f.geometry),
        });
        continue;
      }
      case "building": {
        if (!detail.labels.building) continue;
        if (extent(f.geometry) / detail.blocksPerPixel < 26) continue;
        const p = ringCentroid(f.geometry);
        if (!p) continue;
        out.push({ id: f.id, featureId: f.id, kind: "building", text, anchor: p, priority: KIND_PRIORITY.building });
        continue;
      }
    }
  }
  return out;
}

export interface LabelBox {
  id: string;
  priority: number;
  /** Screen position of the box's top-left corner, in container pixels. */
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Viewport {
  width: number;
  height: number;
}

function overlaps(a: LabelBox, b: LabelBox, pad: number): boolean {
  return (
    a.x - pad < b.x + b.width &&
    b.x - pad < a.x + a.width &&
    a.y - pad < b.y + b.height &&
    b.y - pad < a.y + a.height
  );
}

/**
 * Greedy label placement: highest priority first, dropping anything that overlaps something already
 * kept or sits outside the viewport. This is what keeps a dense town from turning into a wall of
 * overlapping text — the same trade every map renderer makes, and the reason a street name quietly
 * disappears as you zoom out rather than piling up on its neighbours.
 *
 * Deterministic: ties break on id, so the same view always keeps the same labels.
 */
export function placeLabels(
  boxes: readonly LabelBox[],
  viewport: Viewport,
  options: { padding?: number; max?: number } = {},
): Set<string> {
  const pad = options.padding ?? 2;
  const max = options.max ?? 120;
  const kept: LabelBox[] = [];
  const ids = new Set<string>();
  const ordered = [...boxes].sort((a, b) => b.priority - a.priority || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  for (const box of ordered) {
    if (kept.length >= max) break;
    if (box.x + box.width < 0 || box.y + box.height < 0 || box.x > viewport.width || box.y > viewport.height) continue;
    if (kept.some((k) => overlaps(box, k, pad))) continue;
    kept.push(box);
    ids.add(box.id);
  }
  return ids;
}

/** Rough rendered width of a label, in px: enough for greedy placement, no DOM measurement. */
export function labelWidth(text: string, fontSize: number): number {
  return Math.round(text.length * fontSize * 0.58);
}
