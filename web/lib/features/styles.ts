/**
 * Visual styles for features. Plain data (Leaflet-compatible option names), no Leaflet import.
 */
import type { BuildingCategory, Feature, FeatureType, RoadClass } from "../api/types";

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
  main: { color: "#fdf0c6", weight: 6.5, casing: "#7d6428", casingExtra: 3 },
  street: { color: "#ffffff", weight: 4.5, casing: "#4f4a42", casingExtra: 2.5 },
  // Round caps turn a 0-length dash into a dot, the way Apple draws trails and pedestrian paths.
  path: { color: "#ffffff", weight: 2.5, casing: "#5c5348", casingExtra: 2, dashArray: "0.1 7" },
};

/** Used for a station whose railway is not (or no longer) in the feature list. */
export const UNKNOWN_RAILWAY_COLOUR = "#6a6a70";

/** Default railway tint offered in the editor and used for the legend swatch. */
export const DEFAULT_RAILWAY_COLOUR = "#4a4a52";

export function roadStyle(roadClass: RoadClass): FeatureStyle {
  const look = ROAD_LOOKS[roadClass];
  return {
    shape: "line",
    casing: { color: look.casing, weight: look.weight + look.casingExtra, opacity: 0.85, lineCap: "round", lineJoin: "round" },
    main: { color: look.color, weight: look.weight, opacity: 1, lineCap: "round", lineJoin: "round", dashArray: look.dashArray },
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
 */
export function railwayStyle(colour: string): FeatureStyle {
  return {
    shape: "line",
    casing: { color: "#ffffff", weight: 9, opacity: 0.9, lineCap: "round", lineJoin: "round" },
    main: { color: colour, weight: 5, opacity: 1, lineCap: "round", lineJoin: "round" },
    overlay: { color: "#ffffff", weight: 5, opacity: 0.95, dashArray: "3 9", lineCap: "butt" },
  };
}

/** A tinted footprint with a defined darker edge, so it holds up over busy terrain. */
export function buildingStyle(category: BuildingCategory): FeatureStyle {
  return {
    shape: "polygon",
    main: {
      color: CATEGORY_STROKES[category],
      weight: 2,
      opacity: 0.95,
      fillColor: CATEGORY_COLOURS[category],
      fillOpacity: 0.6,
    },
  };
}

/** Transit dot: solid white disc ringed in the line colour. */
export function stationStyle(railwayColour: string): FeatureStyle {
  return {
    shape: "circle",
    main: { radius: 5.5, color: railwayColour, weight: 3.5, opacity: 1, fillColor: "#ffffff", fillOpacity: 1 },
  };
}

/** Style for a feature. `railwayColours` maps railway id -> colour (for stations). */
export function featureStyle(f: Feature, railwayColours: ReadonlyMap<string, string>): FeatureStyle {
  switch (f.type) {
    case "building":
      return buildingStyle(f.props.category);
    case "road":
      return roadStyle(f.props.roadClass);
    case "railway":
      return railwayStyle(f.props.colour);
    case "station":
      return stationStyle(railwayColours.get(f.props.railwayId) ?? UNKNOWN_RAILWAY_COLOUR);
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
