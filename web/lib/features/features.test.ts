import { describe, expect, it } from "vitest";
import type { Feature, RoadClass } from "../api/types";
import { blockBounds, boundsToLatLngs, flyTarget, polylineLength, ringArea, vertexLatLngs } from "./geometry";
import { ALL_VISIBLE, countByType, formatTimestamp, visibleFeatures } from "./layers";
import { searchFeatures } from "./search";
import { BUILDING_CATEGORIES } from "../api/types";
import {
  CATEGORY_COLOURS,
  CATEGORY_LABELS,
  CATEGORY_STROKES,
  displayName,
  drawOrder,
  featureStyle,
  featureSubtitle,
  railwayColourMap,
  UNKNOWN_RAILWAY_COLOUR,
} from "./styles";

const meta = {
  revision: 1,
  createdBy: { uuid: "u", name: "Steve" },
  createdAt: "2026-09-15T10:00:00Z",
  updatedBy: { uuid: "u", name: "Alex" },
  updatedAt: "2026-09-15T11:00:00Z",
};

const road = (id: string, name: string, roadClass: RoadClass = "street"): Feature => ({
  ...meta, id, type: "road", name, geometry: [{ x: 0, z: 0 }, { x: 10, z: 0 }], props: { roadClass },
});
const building: Feature = {
  ...meta, id: "b", type: "building", name: "Town Hall",
  geometry: [{ x: -10, z: -5 }, { x: 10, z: -5 }, { x: 10, z: 20 }], props: { category: "public", description: "" },
};
const railway: Feature = {
  ...meta, id: "r", type: "railway", name: "Red Line", geometry: [{ x: 0, z: 0 }, { x: 0, z: 50 }], props: { colour: "#d62828" },
};
const station: Feature = {
  ...meta, id: "s", type: "station", name: "Central", geometry: [{ x: 0, z: 50 }], props: { railwayId: "r" },
};

describe("styles", () => {
  it("buildings are filled polygons coloured by category", () => {
    const s = featureStyle(building, new Map());
    expect(s.shape).toBe("polygon");
    if (s.shape === "polygon") {
      expect(s.main.fillColor).toBe(CATEGORY_COLOURS.public);
      expect(s.main.fillOpacity).toBeGreaterThan(0);
    }
  });

  it("every building category has a tint, an outline and a label", () => {
    for (const c of BUILDING_CATEGORIES) {
      expect(CATEGORY_COLOURS[c]).toMatch(/^#[0-9a-f]{6}$/);
      expect(CATEGORY_STROKES[c]).toMatch(/^#[0-9a-f]{6}$/);
      expect(CATEGORY_LABELS[c]).toBeTruthy();
    }
    // Tints stay distinguishable: no two categories share one.
    expect(new Set(BUILDING_CATEGORIES.map((c) => CATEGORY_COLOURS[c])).size).toBe(BUILDING_CATEGORIES.length);
  });

  it("road width decreases highway > main > street > path", () => {
    const w = (c: RoadClass) => {
      const s = featureStyle(road("x", "x", c), new Map());
      return s.shape === "line" ? s.main.weight : NaN;
    };
    expect(w("highway")).toBeGreaterThan(w("main"));
    expect(w("main")).toBeGreaterThan(w("street"));
    expect(w("street")).toBeGreaterThan(w("path"));
  });

  it("railways are a solid stroke in their colour, hatched with white sleeper ticks", () => {
    const s = featureStyle(railway, new Map());
    expect(s.shape === "line" && s.main.color).toBe("#d62828");
    // The body is solid; the ticks live on the topmost overlay, not as holes punched in the body.
    expect(s.shape === "line" && s.main.dashArray).toBeUndefined();
    expect(s.shape === "line" && s.casing?.color).toBe("#ffffff");
    expect(s.shape === "line" && s.overlay?.dashArray).toBeTruthy();
  });

  it("roads have no overlay, so they cannot be confused with the railway hatch", () => {
    for (const c of ["highway", "main", "street", "path"] as const) {
      const s = featureStyle(road("x", "x", c), new Map());
      expect(s.shape === "line" && s.overlay).toBeFalsy();
    }
  });

  it("stations are circles stroked with their railway colour (fallback when unknown)", () => {
    const colours = railwayColourMap([railway, building]);
    expect(colours).toEqual(new Map([["r", "#d62828"]]));
    const s = featureStyle(station, colours);
    expect(s.shape === "circle" && s.main.color).toBe("#d62828");
    const orphan = featureStyle(station, new Map());
    expect(orphan.shape === "circle" && orphan.main.color).toBe(UNKNOWN_RAILWAY_COLOUR);
  });

  it("draw order: buildings, roads by class, railways, stations", () => {
    const order = drawOrder([station, road("h", "H", "highway"), railway, road("p", "P", "path"), building]);
    expect(order.map((f) => f.id)).toEqual(["b", "p", "h", "r", "s"]);
  });

  it("subtitles and display names", () => {
    expect(featureSubtitle(building)).toBe("Public building");
    expect(featureSubtitle(road("m", "M", "main"))).toBe("Main road");
    expect(featureSubtitle(station, new Map([["r", "Red Line"]]))).toBe("Station · Red Line");
    expect(featureSubtitle(station)).toBe("Station");
    expect(displayName(road("x", "  "))).toBe("Unnamed road");
  });
});

describe("searchFeatures", () => {
  const all = [road("1", "Main Street"), road("2", "Old Main Road"), road("3", "Remain Lane"), building, station];

  it("is case-insensitive substring; prefix, then word start, then inner matches", () => {
    expect(searchFeatures(all, "MAIN").map((f) => f.id)).toEqual(["1", "2", "3"]);
  });

  it("trims the query; empty query gives nothing", () => {
    expect(searchFeatures(all, "  hall ").map((f) => f.id)).toEqual(["b"]);
    expect(searchFeatures(all, "   ")).toEqual([]);
    expect(searchFeatures(all, "zzz")).toEqual([]);
  });

  it("respects the limit and sorts ties by name", () => {
    const many = [road("b", "Street B"), road("a", "Street A"), road("c", "Street C")];
    expect(searchFeatures(many, "street", 2).map((f) => f.id)).toEqual(["a", "b"]);
  });
});

describe("geometry", () => {
  it("draws vertices at block centre through the squaremap CRS", () => {
    // maxZoom 3 → scale 1/8; (10, -5) → centre (10.5, -4.5) → lat 4.5/8, lng 10.5/8
    expect(vertexLatLngs([{ x: 10, z: -5 }], 3)).toEqual([{ lat: 0.5625, lng: 1.3125 }]);
  });

  it("computes block bounds and LatLng corners (north = -z)", () => {
    const b = blockBounds(building.geometry)!;
    expect(b).toEqual({ minX: -10, minZ: -5, maxX: 10, maxZ: 20 });
    const [sw, ne] = boundsToLatLngs(b, 0);
    expect(sw).toEqual({ lat: -20.5, lng: -9.5 });
    expect(ne).toEqual({ lat: 4.5, lng: 10.5 });
    expect(ne.lat).toBeGreaterThan(sw.lat);
    expect(blockBounds([])).toBeNull();
  });

  it("flies to a point for stations and to bounds otherwise", () => {
    expect(flyTarget(station, 0)).toEqual({ kind: "point", latLng: { lat: -50.5, lng: 0.5 } });
    expect(flyTarget(railway, 0)?.kind).toBe("bounds");
  });
});

describe("measurements", () => {
  it("polyline length and ring area", () => {
    expect(polylineLength([{ x: 0, z: 0 }, { x: 3, z: 4 }, { x: 3, z: 10 }])).toBe(11);
    expect(polylineLength([{ x: 1, z: 1 }])).toBe(0);
    expect(ringArea([{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 10, z: 5 }, { x: 0, z: 5 }])).toBe(50);
    expect(ringArea([{ x: 0, z: 0 }, { x: 0, z: 5 }, { x: 10, z: 5 }, { x: 10, z: 0 }])).toBe(50);
  });
});

describe("layers", () => {
  it("filters by per-type visibility and counts types", () => {
    const all = [building, railway, station, road("1", "x")];
    expect(visibleFeatures(all, ALL_VISIBLE)).toHaveLength(4);
    expect(visibleFeatures(all, { ...ALL_VISIBLE, station: false, road: false }).map((f) => f.id)).toEqual(["b", "r"]);
    expect(countByType(all)).toEqual({ building: 1, road: 1, railway: 1, station: 1 });
  });

  it("formats timestamps, passing through unparseable values", () => {
    expect(formatTimestamp("not a date")).toBe("not a date");
    expect(formatTimestamp("2026-09-15T11:00:00Z", "en-GB")).toMatch(/2026/);
  });
});
