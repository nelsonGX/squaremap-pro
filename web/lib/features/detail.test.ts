import { describe, expect, it } from "vitest";
import type { Feature, RoadClass } from "../api/types";
import { detailKey, FULL_DETAIL, mapDetail, strokeScale } from "./detail";
import { featureStyle } from "./styles";

const meta = {
  revision: 1,
  createdBy: { uuid: "u", name: "Steve" },
  createdAt: "2026-09-15T10:00:00Z",
  updatedBy: { uuid: "u", name: "Alex" },
  updatedAt: "2026-09-15T11:00:00Z",
};

const road = (roadClass: RoadClass): Feature => ({
  ...meta, id: "r", type: "road", name: "Main Street",
  geometry: [{ x: 0, z: 0 }, { x: 400, z: 0 }], props: { roadClass },
});
const railway: Feature = {
  ...meta, id: "l", type: "railway", name: "Red Line",
  geometry: [{ x: 0, z: 0 }, { x: 0, z: 400 }], props: { colour: "#d62828" },
};

/** squaremap's default world: zoom.max 3, so map zoom 3 is one block per pixel. */
const MAX_ZOOM = 3;

describe("mapDetail", () => {
  it("is full detail at the native max zoom", () => {
    const d = mapDetail(MAX_ZOOM, MAX_ZOOM);
    expect(d.blocksPerPixel).toBe(1);
    expect(d.scale).toBe(1);
    expect(d).toMatchObject({ buildings: true, stations: "all", railDetail: true });
  });

  it("thins the network and drops footprints, dots and rail ticks as it zooms out", () => {
    const close = mapDetail(MAX_ZOOM - 1, MAX_ZOOM); // 2 blocks/px
    const mid = mapDetail(MAX_ZOOM - 2, MAX_ZOOM); // 4
    const far = mapDetail(0, MAX_ZOOM); // 8 — the whole-world view

    expect(close.scale).toBeLessThan(FULL_DETAIL.scale);
    expect(mid.scale).toBeLessThan(close.scale);
    expect(far.scale).toBeLessThan(mid.scale);

    expect(close).toMatchObject({ buildings: true, stations: "all", railDetail: true });
    expect(mid).toMatchObject({ buildings: true, stations: "interchanges", railDetail: false });
    expect(far).toMatchObject({ buildings: false, stations: "interchanges", railDetail: false });
  });

  it("keeps line names longest, and shows road and building names only when zoomed in", () => {
    expect(mapDetail(0, MAX_ZOOM).labels).toMatchObject({ railway: true, station: false, road: false });
    expect(mapDetail(MAX_ZOOM, MAX_ZOOM).labels).toMatchObject({ railway: true, station: true, road: true });
    expect(mapDetail(MAX_ZOOM + 1, MAX_ZOOM).labels.building).toBe(true);
  });

  it("clamps the stroke scale at both ends", () => {
    expect(strokeScale(1024)).toBe(0.4);
    expect(strokeScale(1 / 1024)).toBe(1.15);
    expect(strokeScale(1)).toBe(1);
  });

  it("detailKey only changes when something visible changes", () => {
    // Zoom past the native max: same level of detail, different Leaflet zoom.
    expect(detailKey(mapDetail(MAX_ZOOM + 1, MAX_ZOOM))).toBe(detailKey(mapDetail(MAX_ZOOM + 2, MAX_ZOOM)));
    expect(detailKey(mapDetail(0, MAX_ZOOM))).not.toBe(detailKey(mapDetail(MAX_ZOOM, MAX_ZOOM)));
  });
});

describe("zoom-scaled styles", () => {
  const weight = (f: Feature, zoom: number) => {
    const s = featureStyle(f, new Map(), mapDetail(zoom, MAX_ZOOM));
    return s.shape === "line" ? s.main.weight : NaN;
  };

  it("shrinks strokes as the view zooms out", () => {
    expect(weight(road("highway"), 0)).toBeLessThan(weight(road("highway"), MAX_ZOOM));
  });

  it("keeps the road hierarchy at every zoom", () => {
    for (const z of [0, 1, 2, 3]) {
      expect(weight(road("highway"), z)).toBeGreaterThan(weight(road("street"), z));
    }
  });

  it("never thins a line away entirely, and always keeps a casing around it", () => {
    const s = featureStyle(road("path"), new Map(), mapDetail(0, MAX_ZOOM));
    expect(s.shape === "line" && s.main.weight).toBeGreaterThanOrEqual(0.8);
    expect(s.shape === "line" && (s.casing?.weight ?? 0)).toBeGreaterThan(s.shape === "line" ? s.main.weight : 0);
  });

  it("rail is subordinate to a street, and drops its hatch when zoomed out", () => {
    expect(weight(railway, MAX_ZOOM)).toBeLessThan(weight(road("street"), MAX_ZOOM));
    const near = featureStyle(railway, new Map(), mapDetail(MAX_ZOOM, MAX_ZOOM));
    const far = featureStyle(railway, new Map(), mapDetail(0, MAX_ZOOM));
    expect(near.shape === "line" && near.overlay?.dashArray).toBeTruthy();
    expect(far.shape === "line" && far.overlay).toBeFalsy();
    expect(far.shape === "line" && far.casing?.color).not.toBe("#ffffff");
  });

  it("scales a dash pattern with the stroke, keeping its proportions", () => {
    const near = featureStyle(road("path"), new Map(), FULL_DETAIL);
    const far = featureStyle(road("path"), new Map(), mapDetail(0, MAX_ZOOM));
    const gap = (s: ReturnType<typeof featureStyle>) =>
      s.shape === "line" ? Number(s.main.dashArray!.split(" ")[1]) : NaN;
    expect(gap(far)).toBeLessThan(gap(near));
    expect(gap(far)).toBeGreaterThan(0);
  });

  it("station dots shrink less than lines, so they stay clickable", () => {
    const radius = (zoom: number) => {
      const s = featureStyle(
        { ...meta, id: "s", type: "station", name: "Central", geometry: [{ x: 0, z: 0 }], props: { railwayId: "l" } },
        new Map([["l", "#d62828"]]),
        mapDetail(zoom, MAX_ZOOM),
      );
      return s.shape === "circle" ? s.main.radius : NaN;
    };
    expect(radius(0)).toBeLessThan(radius(MAX_ZOOM));
    expect(radius(0)).toBeGreaterThanOrEqual(3.5);
  });
});
