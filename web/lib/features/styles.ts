/**
 * Visual styles for features. Plain data (Leaflet-compatible option names), no Leaflet import.
 */
import type { BuildingCategory, Feature, FeatureType, RoadClass } from "../api/types";
import { FULL_DETAIL, type MapDetail } from "./detail";

export interface LineStyle {
  color: string;
  weight: number;
  opacity: number;
  dashArray?: string;
  lineCap?: "butt" | "round" | "square";
  lineJoin?: "miter" | "round" | "bevel";
}

export interface PolygonStyle extends LineStyle {
  fillColor: string;
  fillOpacity: number;
}

export interface CircleStyle extends PolygonStyle {
  radius: number;
}

/**
 * A feature is drawn as up to three stacked strokes: an optional casing under the interactive main
 * shape, and — for railways — an optional `overlay` on top of it. Casing and overlay are both
 * non-interactive, so clicks and tooltips always land on `main`.
 */
export type FeatureStyle =
  | { shape: "polygon"; main: PolygonStyle }
  | { shape: "line"; casing: LineStyle | null; main: LineStyle; overlay?: LineStyle | null }
  | { shape: "circle"; main: CircleStyle };

export const TYPE_LABELS: Record<FeatureType, string> = {
  building: "Building",
  road: "Road",
  railway: "Railway",
  station: "Station",
};

export const TYPE_PLURAL_LABELS: Record<FeatureType, string> = {
  building: "Buildings",
  road: "Roads",
  railway: "Railways",
  station: "Stations",
};

export const CATEGORY_LABELS: Record<BuildingCategory, string> = {
  residential: "Residential",
  commercial: "Commercial",
  public: "Public",
  industrial: "Industrial",
  government: "Government",
  education: "Education",
  healthcare: "Healthcare",
  religious: "Religious",
  farm: "Farm",
  storage: "Storage",
  landmark: "Landmark",
  other: "Other",
};

export const ROAD_CLASS_LABELS: Record<RoadClass, string> = {
  highway: "Highway",
  main: "Main road",
  street: "Street",
  path: "Path",
};

/**
 * Building tints, in the muted Apple Maps register: warm earth for homes, amber for retail,
 * indigo for civic buildings, slate for industry and system grey for everything else. They are
 * deliberately low-chroma so they sit under the road network rather than fighting it.
 */
export const CATEGORY_COLOURS: Record<BuildingCategory, string> = {
  residential: "#bf8a5c",
  commercial: "#e8912f",
  public: "#6d5fc7",
  industrial: "#6a7d8c",
  government: "#5b7fa6",
  education: "#a9689b",
  healthcare: "#c9645e",
  religious: "#b39a5f",
  farm: "#7d9a55",
  storage: "#8b7a63",
  landmark: "#4f9a92",
  other: "#98989f",
};

/**
 * Outline per category: a darkened shade of the fill. squaremap tiles are noisy pixel art, so a
 * footprint needs a defined dark edge to read as a building rather than as a stain on the terrain.
 */
export const CATEGORY_STROKES: Record<BuildingCategory, string> = {
  residential: "#7d5730",
  commercial: "#9c5a0c",
  public: "#3f3490",
  industrial: "#3c4c58",
  government: "#33506d",
  education: "#6d3d64",
  healthcare: "#8a3b36",
  religious: "#756139",
  farm: "#4e6631",
  storage: "#5a4d3b",
  landmark: "#2d635d",
  other: "#5c5c63",
};

interface RoadLook {
  color: string;
  weight: number;
  casing: string;
  casingExtra: number;
  dashArray?: string;
}

/**
 * Apple Maps' road hierarchy: motorways a saturated gold, arterials a pale cream, streets plain
 * white, footpaths a thin dotted trail.
 *
 * Each class is a light fill inside a *much* darker casing. Apple can use a pale casing because it
 * draws its own flat land underneath; here the backdrop is arbitrary pixel art — tan desert, dark
 * forest, blue ocean — so only a strongly contrasting edge keeps a road legible everywhere. With a
 * pale casing the network read as bare white scribbles lying on top of the terrain.
 */
export const ROAD_LOOKS: Record<RoadClass, RoadLook> = {
  highway: { color: "#fcc14b", weight: 8, casing: "#8a5a06", casingExtra: 3.5 },
  main: { color: "#fdf0c6", weight: 6.5, casing: "#6f5820", casingExtra: 3 },
  street: { color: "#ffffff", weight: 4.5, casing: "#33302b", casingExtra: 2.5 },
  // Round caps turn a 0-length dash into a dot, the way Apple draws trails and pedestrian paths.
  path: { color: "#ffffff", weight: 2.5, casing: "#443e35", casingExtra: 2, dashArray: "0.1 7" },
};

/** Used for a station whose railway is not (or no longer) in the feature list. */
export const UNKNOWN_RAILWAY_COLOUR = "#6a6a70";

/** Default railway tint offered in the editor and used for the legend swatch. */
export const DEFAULT_RAILWAY_COLOUR = "#4a4a52";

/**
 * Rounds a scaled weight to a tenth of a pixel, with a floor: below ~0.6 px a canvas stroke fades
 * to a grey smear, so a thinned-out path still has to be a line you can see and click.
 */
function scaled(weight: number, scale: number, min = 0.8): number {
  return Math.max(min, Math.round(weight * scale * 10) / 10);
}

/** Dash pattern scaled with the stroke, so a thinned line keeps its dash *proportions*. */
function scaledDash(dashArray: string, scale: number): string {
  return dashArray
    .split(/[\s,]+/)
    .filter((n) => n !== "")
    .map((n) => Math.max(0.1, Math.round(Number(n) * scale * 10) / 10))
    .join(" ");
}

export function roadStyle(roadClass: RoadClass, detail: MapDetail = FULL_DETAIL): FeatureStyle {
  const look = ROAD_LOOKS[roadClass];
  const scale = detail.scale;
  const weight = scaled(look.weight, scale);
  /*
   * The casing is what makes a road legible over arbitrary pixel art, so it is never allowed to
   * thin away entirely: it keeps at least a pixel of dark edge on each side however far out we are.
   */
  const casingWeight = Math.max(weight + 1.2, scaled(look.weight + look.casingExtra, scale));
  return {
    shape: "line",
    casing: { color: look.casing, weight: casingWeight, opacity: 0.85, lineCap: "round", lineJoin: "round" },
    main: {
      color: look.color,
      weight,
      opacity: 1,
      lineCap: "round",
      lineJoin: "round",
      dashArray: look.dashArray === undefined ? undefined : scaledDash(look.dashArray, scale),
    },
  };
}

/**
 * The standard railway hatch: the line's colour as a solid body inside a white halo, with short
 * white "sleeper" ticks laid across it.
 *
 * A plain solid stroke (what this was) is indistinguishable from a road at map zoom — both end up
 * as a coloured line with a contrasting edge. The ticks are what make a railway read as a railway,
 * and they cannot be a dash on `main` because that would punch holes through to the terrain rather
 * than mark the body; hence the separate topmost `overlay`.
 *
 * Rail is *subordinate* to road on a general-purpose map — a network only some places can use —
 * so its body sits below a street's and, once `detail.railDetail` is off, it drops the halo and the
 * ticks entirely: at the world view they stop reading as sleepers and turn the line into a
 * candy-stripe that shouts over the road network.
 */
export function railwayStyle(colour: string, detail: MapDetail = FULL_DETAIL): FeatureStyle {
  const scale = detail.scale;
  const weight = scaled(3.6, scale);
  if (!detail.railDetail) {
    return {
      shape: "line",
      casing: { color: "#2a2a30", weight: weight + 1.2, opacity: 0.5, lineCap: "round", lineJoin: "round" },
      main: { color: colour, weight, opacity: 1, lineCap: "round", lineJoin: "round" },
      overlay: null,
    };
  }
  return {
    shape: "line",
    casing: { color: "#ffffff", weight: scaled(6.4, scale), opacity: 0.9, lineCap: "round", lineJoin: "round" },
    main: { color: colour, weight, opacity: 1, lineCap: "round", lineJoin: "round" },
    overlay: { color: "#ffffff", weight, opacity: 0.95, dashArray: scaledDash("3 9", scale), lineCap: "butt" },
  };
}

/** A tinted footprint with a defined darker edge, so it holds up over busy terrain. */
export function buildingStyle(category: BuildingCategory, detail: MapDetail = FULL_DETAIL): FeatureStyle {
  return {
    shape: "polygon",
    main: {
      color: CATEGORY_STROKES[category],
      weight: scaled(2, detail.scale, 0.5),
      opacity: 0.95,
      fillColor: CATEGORY_COLOURS[category],
      fillOpacity: 0.6,
    },
  };
}

/** Transit dot: solid white disc ringed in the line colour. */
export function stationStyle(railwayColour: string, detail: MapDetail = FULL_DETAIL): FeatureStyle {
  // A dot below ~4 px stops reading as a station, so it shrinks less than the lines do.
  const scale = Math.max(detail.scale, 0.7);
  return {
    shape: "circle",
    main: {
      radius: Math.round(5.5 * scale * 10) / 10,
      color: railwayColour,
      weight: scaled(3.5, scale, 1),
      opacity: 1,
      fillColor: "#ffffff",
      fillOpacity: 1,
    },
  };
}

/** Style for a feature. `railwayColours` maps railway id -> colour (for stations). */
export function featureStyle(
  f: Feature,
  railwayColours: ReadonlyMap<string, string>,
  detail: MapDetail = FULL_DETAIL,
): FeatureStyle {
  switch (f.type) {
    case "building":
      return buildingStyle(f.props.category, detail);
    case "road":
      return roadStyle(f.props.roadClass, detail);
    case "railway":
      return railwayStyle(f.props.colour, detail);
    case "station":
      return stationStyle(railwayColours.get(f.props.railwayId) ?? UNKNOWN_RAILWAY_COLOUR, detail);
  }
}

export function railwayColourMap(features: readonly Feature[]): Map<string, string> {
  const m = new Map<string, string>();
  for (const f of features) if (f.type === "railway") m.set(f.id, f.props.colour);
  return m;
}

/**
 * Draw order (bottom to top): buildings, roads (path < street < main < highway), railways, stations.
 * Returns a new array; stable for equal keys.
 */
export function drawOrder(features: readonly Feature[]): Feature[] {
  const roadRank: Record<RoadClass, number> = { path: 0, street: 1, main: 2, highway: 3 };
  const rank = (f: Feature): number => {
    switch (f.type) {
      case "building":
        return 0;
      case "road":
        return 10 + roadRank[f.props.roadClass];
      case "railway":
        return 20;
      case "station":
        return 30;
    }
  };
  return features
    .map((f, i) => ({ f, i, r: rank(f) }))
    .sort((a, b) => a.r - b.r || a.i - b.i)
    .map((e) => e.f);
}

/** One-line subtitle: type plus class / category. */
export function featureSubtitle(f: Feature, railwayNames?: ReadonlyMap<string, string>): string {
  switch (f.type) {
    case "building":
      return `${CATEGORY_LABELS[f.props.category]} building`;
    case "road":
      return ROAD_CLASS_LABELS[f.props.roadClass];
    case "railway":
      return "Railway";
    case "station": {
      const line = railwayNames?.get(f.props.railwayId);
      return line ? `Station · ${line}` : "Station";
    }
  }
}

/** Display name: the feature name, or "Unnamed <type>" when blank. */
export function displayName(f: Feature): string {
  const n = f.name.trim();
  return n === "" ? `Unnamed ${TYPE_LABELS[f.type].toLowerCase()}` : n;
}
