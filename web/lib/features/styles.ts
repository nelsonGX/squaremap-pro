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
}

export interface PolygonStyle extends LineStyle {
  fillColor: string;
  fillOpacity: number;
}

export interface CircleStyle extends PolygonStyle {
  radius: number;
}

/** A feature is drawn as an optional non-interactive casing under the interactive main shape. */
export type FeatureStyle =
  | { shape: "polygon"; main: PolygonStyle }
  | { shape: "line"; casing: LineStyle | null; main: LineStyle }
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

export const CATEGORY_COLOURS: Record<BuildingCategory, string> = {
  residential: "#e76f51",
  commercial: "#2a9df4",
  public: "#9b5de5",
  industrial: "#6c757d",
  other: "#c9a227",
};

interface RoadLook {
  color: string;
  weight: number;
  casing: string;
  casingExtra: number;
  dashArray?: string;
}

/** Highway thickest, path thinnest (and dashed). */
export const ROAD_LOOKS: Record<RoadClass, RoadLook> = {
  highway: { color: "#f59e0b", weight: 9, casing: "#92400e", casingExtra: 3 },
  main: { color: "#fde68a", weight: 7, casing: "#a16207", casingExtra: 2 },
  street: { color: "#ffffff", weight: 5, casing: "#6b7280", casingExtra: 2 },
  path: { color: "#e7d7b1", weight: 2.5, casing: "#57534e", casingExtra: 1.5, dashArray: "4 4" },
};

/** Used for a station whose railway is not (or no longer) in the feature list. */
export const UNKNOWN_RAILWAY_COLOUR = "#374151";

export function roadStyle(roadClass: RoadClass): FeatureStyle {
  const look = ROAD_LOOKS[roadClass];
  return {
    shape: "line",
    casing: { color: look.casing, weight: look.weight + look.casingExtra, opacity: 0.9, lineCap: "round" },
    main: { color: look.color, weight: look.weight, opacity: 1, lineCap: "round", dashArray: look.dashArray },
  };
}

export function railwayStyle(colour: string): FeatureStyle {
  return {
    shape: "line",
    casing: { color: "#ffffff", weight: 7, opacity: 0.75, lineCap: "butt" },
    main: { color: colour, weight: 4, opacity: 1, dashArray: "12 8", lineCap: "butt" },
  };
}

export function buildingStyle(category: BuildingCategory): FeatureStyle {
  const c = CATEGORY_COLOURS[category];
  return { shape: "polygon", main: { color: c, weight: 2, opacity: 0.95, fillColor: c, fillOpacity: 0.4 } };
}

export function stationStyle(railwayColour: string): FeatureStyle {
  return {
    shape: "circle",
    main: { radius: 6, color: railwayColour, weight: 3, opacity: 1, fillColor: "#ffffff", fillOpacity: 1 },
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
