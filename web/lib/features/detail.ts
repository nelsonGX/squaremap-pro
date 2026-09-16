/**
 * Level of detail: what the map draws at the current zoom. Pure (no Leaflet, no React).
 *
 * Feature styles are authored at "one block = one screen pixel" (squaremap's `zoom.max`), which is
 * the only zoom where a 6-block-wide street should be drawn 6 px wide. Every other zoom needs the
 * whole network rescaled and thinned out: at the world view a fixed 8 px highway covers hundreds of
 * blocks, the railway hatch degenerates into a candy-stripe, and 44 building footprints are
 * sub-pixel noise — which is how the map ended up reading as ribbon spaghetti over the terrain.
 *
 * Everything keys off **blocks per pixel** rather than the raw Leaflet zoom, because squaremap's
 * `zoom.max` / `zoom.extra` differ per world: bpp is comparable across worlds and is what actually
 * decides whether a thing is legible.
 */
import { blocksPerPixel } from "../editor/geometry";

/** Which station markers are worth drawing. */
export type StationDetail = "all" | "interchanges" | "none";

export interface LabelDetail {
  station: boolean;
  railway: boolean;
  road: boolean;
  building: boolean;
}

export interface MapDetail {
  /** Blocks per screen pixel; 1 at the world's native max zoom, larger when zoomed out. */
  readonly blocksPerPixel: number;
  /** Multiplier applied to every stroke weight and marker radius. */
  readonly scale: number;
  /** Draw building footprints at all. */
  readonly buildings: boolean;
  readonly stations: StationDetail;
  /** Railway white halo + sleeper ticks (a candy-stripe once a dash is thinner than a few pixels). */
  readonly railDetail: boolean;
  readonly labels: LabelDetail;
}

/** Style weights are authored at this detail: native zoom, everything on. */
export const FULL_DETAIL: MapDetail = {
  blocksPerPixel: 1,
  scale: 1,
  buildings: true,
  stations: "all",
  railDetail: true,
  labels: { station: true, railway: true, road: true, building: false },
};

const MIN_SCALE = 0.4;
const MAX_SCALE = 1.15;

/**
 * Stroke scale for a given blocks-per-pixel.
 *
 * `bpp ** -0.5` — halving the scale for every 4x zoom-out — rather than a straight `1 / bpp`: a road
 * is a *symbol*, not a footprint, so it has to stay clickable and visible at the world view, the way
 * Apple and Google keep motorways a couple of pixels wide on a continental map. Clamped at both ends
 * so deep zoom-in does not inflate a street into a slab.
 */
export function strokeScale(blocksPerPixel: number): number {
  const bpp = Math.max(blocksPerPixel, 1e-6);
  const raw = Math.pow(bpp, -0.5);
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.round(raw * 20) / 20));
}

/** Level of detail for a Leaflet zoom on a world whose native max zoom is `maxZoom`. */
export function mapDetail(zoom: number, maxZoom: number): MapDetail {
  const bpp = blocksPerPixel(zoom, maxZoom);
  return {
    blocksPerPixel: bpp,
    scale: strokeScale(bpp),
    buildings: bpp <= 4,
    stations: bpp <= 2 ? "all" : bpp <= 8 ? "interchanges" : "none",
    railDetail: bpp <= 2,
    labels: {
      station: bpp <= 2,
      // Line names survive furthest out: at the world view they are the only thing telling the
      // red dashes from the green ones, and they stand in for a legend.
      railway: bpp <= 8,
      road: bpp <= 1,
      building: bpp <= 0.5,
    },
  };
}

/**
 * A key changing only when something visible changes, so the map rebuilds its layers per zoom
 * *step* instead of on every animation frame.
 */
export function detailKey(d: MapDetail): string {
  const l = d.labels;
  return [d.scale, d.buildings, d.stations, d.railDetail, l.station, l.railway, l.road, l.building].join("|");
}
