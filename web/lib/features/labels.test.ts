import { describe, expect, it } from "vitest";
import type { Feature, RoadClass } from "../api/types";
import { FULL_DETAIL, mapDetail } from "./detail";
import { groupInterchanges } from "./interchange";
import { labelCandidates, labelWidth, placeLabels, polylineMidpoint, ringCentroid, type LabelBox } from "./labels";

const meta = {
  revision: 1,
  createdBy: { uuid: "u", name: "Steve" },
  createdAt: "2026-09-15T10:00:00Z",
  updatedBy: { uuid: "u", name: "Alex" },
  updatedAt: "2026-09-15T11:00:00Z",
};

const road = (id: string, name: string, roadClass: RoadClass = "street", length = 400): Feature => ({
  ...meta, id, type: "road", name,
  geometry: [{ x: 0, z: 0 }, { x: length, z: 0 }], props: { roadClass },
});
const railway: Feature = {
  ...meta, id: "l", type: "railway", name: "Red Line",
  geometry: [{ x: 0, z: 0 }, { x: 0, z: 400 }], props: { colour: "#d62828" },
};
const station = (id: string, name: string, x: number, z: number, railwayId = "l"): Feature => ({
  ...meta, id, type: "station", name, geometry: [{ x, z }], props: { railwayId },
});
const building: Feature = {
  ...meta, id: "b", type: "building", name: "Town Hall",
  geometry: [{ x: 0, z: 0 }, { x: 40, z: 0 }, { x: 40, z: 40 }, { x: 0, z: 40 }],
  props: { category: "public", description: "" },
};

const colours = new Map([["l", "#d62828"]]);
const detailAt = (zoom: number) => mapDetail(zoom, 3);
const ids = (fs: Feature[], detail = FULL_DETAIL, inter = groupInterchanges(fs)) =>
  labelCandidates(fs, inter, detail, colours).map((c) => c.id);

describe("anchors", () => {
  it("puts a polyline label halfway along its length, not halfway along its vertices", () => {
    // Vertices bunch at the start; the midpoint by length is still the middle of the drawn line.
    expect(polylineMidpoint([{ x: 0, z: 0 }, { x: 1, z: 0 }, { x: 2, z: 0 }, { x: 100, z: 0 }]))
      .toEqual({ x: 50, z: 0 });
    expect(polylineMidpoint([{ x: 7, z: 3 }])).toEqual({ x: 7, z: 3 });
    expect(polylineMidpoint([])).toBeNull();
  });

  it("puts a building label at the ring centroid, falling back to the vertex mean", () => {
    expect(ringCentroid(building.geometry)).toEqual({ x: 20, z: 20 });
    // A zero-area ring has no centroid; the mean keeps the label somewhere sensible.
    expect(ringCentroid([{ x: 0, z: 0 }, { x: 10, z: 0 }, { x: 20, z: 0 }])).toEqual({ x: 10, z: 0 });
    expect(ringCentroid([])).toBeNull();
  });
});

describe("labelCandidates", () => {
  it("labels only what the level of detail allows", () => {
    const all = [building, road("r", "Main Street"), railway, station("s", "Central", 0, 400)];
    // World view: line names only.
    expect(ids(all, detailAt(0))).toEqual(["l"]);
    // Native zoom: transit, lines and roads, but not footprints.
    expect(ids(all, detailAt(3)).sort()).toEqual(["l", "r", "s"]);
    // Zoomed in past native: footprints too.
    expect(ids(all, detailAt(4)).sort()).toEqual(["b", "l", "r", "s"]);
  });

  it("skips unnamed features rather than printing 'Unnamed road' on the map", () => {
    expect(ids([road("r", "   ")])).toEqual([]);
  });

  it("skips a feature drawn shorter than its own label", () => {
    const stub = road("short", "Alleyway", "street", 12);
    expect(ids([stub], detailAt(3))).toEqual([]);
    expect(ids([stub], detailAt(6))).toEqual(["short"]);
  });

  it("labels an interchange once, instead of stacking its members' names", () => {
    const members = [station("s1", "Union", 0, 400), station("s2", "Union", 3, 402, "l2")];
    const cs = labelCandidates(members, groupInterchanges(members), FULL_DETAIL, colours);
    expect(cs).toHaveLength(1);
    expect(cs[0]).toMatchObject({ kind: "interchange", text: "Union", featureId: "s1" });
    // The joint label sits at the group's point, not on either member.
    expect(cs[0]!.anchor).toEqual(groupInterchanges(members)[0]!.point);
  });

  it("ranks transit over roads, and a highway over a street", () => {
    const fs = [railway, station("s", "Central", 0, 400), road("h", "Ring Road", "highway"), road("r", "Lane")];
    const byId = new Map(labelCandidates(fs, groupInterchanges(fs), FULL_DETAIL, colours).map((c) => [c.id, c]));
    expect(byId.get("s")!.priority).toBeGreaterThan(byId.get("l")!.priority);
    expect(byId.get("l")!.priority).toBeGreaterThan(byId.get("h")!.priority);
    expect(byId.get("h")!.priority).toBeGreaterThan(byId.get("r")!.priority);
  });

  it("carries the line colour, so a railway label can be drawn as its pill", () => {
    const cs = labelCandidates([railway, station("s", "Central", 0, 400)], [], FULL_DETAIL, colours);
    expect(cs.every((c) => c.colour === "#d62828")).toBe(true);
  });
});

describe("placeLabels", () => {
  const box = (id: string, priority: number, x: number, y: number): LabelBox =>
    ({ id, priority, x, y, width: 60, height: 16 });

  it("keeps the higher priority label when two collide", () => {
    const kept = placeLabels([box("low", 1, 10, 10), box("high", 9, 20, 12)], { width: 800, height: 600 });
    expect([...kept]).toEqual(["high"]);
  });

  it("keeps both when they do not overlap", () => {
    const kept = placeLabels([box("a", 1, 10, 10), box("b", 2, 10, 200)], { width: 800, height: 600 });
    expect(kept.size).toBe(2);
  });

  it("drops labels outside the viewport", () => {
    const kept = placeLabels([box("off", 9, -400, 10), box("on", 1, 10, 10)], { width: 800, height: 600 });
    expect([...kept]).toEqual(["on"]);
  });

  it("is deterministic for equal priorities", () => {
    const boxes = [box("b", 5, 10, 10), box("a", 5, 12, 12)];
    expect([...placeLabels(boxes, { width: 800, height: 600 })]).toEqual(["a"]);
    expect([...placeLabels([...boxes].reverse(), { width: 800, height: 600 })]).toEqual(["a"]);
  });

  it("caps how many labels a dense view draws", () => {
    const many = Array.from({ length: 50 }, (_, i) => box(`b${i}`, 1, 10, i * 40));
    expect(placeLabels(many, { width: 800, height: 4000 }, { max: 10 }).size).toBe(10);
  });

  it("estimates a wider box for a longer name", () => {
    expect(labelWidth("Central", 12)).toBeLessThan(labelWidth("Central Station East", 12));
  });
});
