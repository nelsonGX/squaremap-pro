/**
 * Interchanges: stations belonging to different railways that are close enough together to be one
 * place.
 *
 * A station carries exactly one `railwayId` and must sit on a vertex of that railway, so a crossing
 * served by three lines is three station features. Two lines that cross rarely share a vertex —
 * their nearest vertices can be several blocks apart — so grouping on an exact point would almost
 * never fire. Instead stations merge when they are within `INTERCHANGE_RADIUS_BLOCKS` of each other.
 *
 * Pure: plain data in, plain data out, no Leaflet and no React.
 */
import type { Feature, StationFeature, XZ } from "../api/types";

/** How close two stations must be (in blocks, 2D) to count as the same place. */
export const INTERCHANGE_RADIUS_BLOCKS = 10;

export interface Interchange {
  /** Stable across renders: derived from the member ids, so it does not change as features reload. */
  id: string;
  /** Where to draw the interchange: the mean of its members' points, rounded to a block. */
  point: XZ;
  /** Members, ordered by name then id so the rendering and the label are deterministic. */
  stations: readonly StationFeature[];
  /** Distinct railway ids served here, in member order. */
  railwayIds: readonly string[];
}

function isStation(f: Feature): f is StationFeature {
  return f.type === "station";
}

function distance(a: XZ, b: XZ): number {
  return Math.hypot(a.x - b.x, a.z - b.z);
}

function stationPoint(s: StationFeature): XZ {
  return s.geometry[0]!;
}

/**
 * Groups stations into interchanges by proximity.
 *
 * Single-link clustering: a station joins a group if it is within `radius` of *any* member. A line
 * of stations spaced just under the radius therefore chains into one group, which is the intended
 * reading of "merge what is within 10 blocks" — a platform strung along a junction is one place.
 *
 * Returns one entry per group, including lone stations (a group of one).
 */
export function groupInterchanges(
  features: readonly Feature[],
  radius: number = INTERCHANGE_RADIUS_BLOCKS,
): Interchange[] {
  const stations = features.filter(isStation).filter((s) => s.geometry.length > 0);

  // Union-find over the station list; `find` path-compresses.
  const parent = stations.map((_, i) => i);
  const find = (i: number): number => {
    let r = i;
    while (parent[r] !== r) r = parent[r]!;
    for (let c = i; parent[c] !== r; ) {
      const next = parent[c]!;
      parent[c] = r;
      c = next;
    }
    return r;
  };
  for (let i = 0; i < stations.length; i++) {
    for (let j = i + 1; j < stations.length; j++) {
      if (distance(stationPoint(stations[i]!), stationPoint(stations[j]!)) > radius) continue;
      const a = find(i);
      const b = find(j);
      if (a !== b) parent[a] = b;
    }
  }

  const byRoot = new Map<number, StationFeature[]>();
  for (let i = 0; i < stations.length; i++) {
    const root = find(i);
    const bucket = byRoot.get(root);
    if (bucket) bucket.push(stations[i]!);
    else byRoot.set(root, [stations[i]!]);
  }

  const out: Interchange[] = [];
  for (const members of byRoot.values()) {
    const ordered = [...members].sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
    const sum = ordered.reduce((acc, s) => ({ x: acc.x + stationPoint(s).x, z: acc.z + stationPoint(s).z }), { x: 0, z: 0 });
    const railwayIds: string[] = [];
    for (const s of ordered) if (!railwayIds.includes(s.props.railwayId)) railwayIds.push(s.props.railwayId);
    out.push({
      id: ordered.map((s) => s.id).join("+"),
      point: { x: Math.round(sum.x / ordered.length), z: Math.round(sum.z / ordered.length) },
      stations: ordered,
      railwayIds,
    });
  }
  // Deterministic order, so the draw order and any list built from this is stable.
  return out.sort((a, b) => a.id.localeCompare(b.id));
}

/** The interchange containing `stationId`, or null when the id is not a station in `features`. */
export function interchangeOf(
  features: readonly Feature[],
  stationId: string,
  radius: number = INTERCHANGE_RADIUS_BLOCKS,
): Interchange | null {
  return groupInterchanges(features, radius).find((g) => g.stations.some((s) => s.id === stationId)) ?? null;
}

/** Other stations sharing an interchange with `stationId` (empty when it stands alone). */
export function interchangePeers(
  features: readonly Feature[],
  stationId: string,
  radius: number = INTERCHANGE_RADIUS_BLOCKS,
): StationFeature[] {
  const g = interchangeOf(features, stationId, radius);
  return g ? g.stations.filter((s) => s.id !== stationId) : [];
}

/**
 * Label for an interchange: the distinct member names in order, joined with " / ".
 *
 * Interchange members usually share a name ("Central" on two lines), in which case this is just
 * that name; where operators named them differently both names show.
 */
export function interchangeLabel(g: Interchange, unnamed: (s: StationFeature) => string): string {
  const names: string[] = [];
  for (const s of g.stations) {
    const n = s.name.trim() === "" ? unnamed(s) : s.name.trim();
    if (!names.includes(n)) names.push(n);
  }
  return names.join(" / ");
}

/** Line colours served at an interchange, in member order, for the split ring. */
export function interchangeColours(g: Interchange, railwayColours: ReadonlyMap<string, string>, fallback: string): string[] {
  return g.railwayIds.map((id) => railwayColours.get(id) ?? fallback);
}
