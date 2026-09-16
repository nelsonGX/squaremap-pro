import { describe, expect, it } from "vitest";
import type { Feature, StationFeature } from "../api/types";
import {
  groupInterchanges,
  INTERCHANGE_RADIUS_BLOCKS,
  interchangeColours,
  interchangeLabel,
  interchangeOf,
  interchangePeers,
} from "./interchange";

const meta = {
  revision: 1,
  createdBy: { uuid: "u", name: "Steve" },
  createdAt: "2026-09-15T10:00:00Z",
  updatedBy: { uuid: "u", name: "Alex" },
  updatedAt: "2026-09-15T11:00:00Z",
};

const stn = (id: string, name: string, x: number, z: number, railwayId: string): StationFeature => ({
  ...meta, id, type: "station", name, geometry: [{ x, z }], props: { railwayId },
});
const railway = (id: string, colour: string): Feature => ({
  ...meta, id, type: "railway", name: id, geometry: [{ x: 0, z: 0 }, { x: 0, z: 50 }], props: { colour },
});

describe("groupInterchanges", () => {
  it("merges stations of different lines that are within the radius", () => {
    // 8 blocks apart: inside the 10-block radius, so one interchange serving both lines.
    const groups = groupInterchanges([stn("a", "Central", 100, 0, "red"), stn("b", "Central", 106, -5, "green")]);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.stations.map((s) => s.id)).toEqual(["a", "b"]);
    expect(groups[0]!.railwayIds).toEqual(["red", "green"]);
  });

  it("leaves stations further apart than the radius separate", () => {
    const groups = groupInterchanges([stn("a", "North", 0, 0, "red"), stn("b", "South", 0, 40, "green")]);
    expect(groups).toHaveLength(2);
    expect(groups.every((g) => g.stations.length === 1)).toBe(true);
  });

  it("uses exactly the radius as the cutoff", () => {
    const at = (d: number) => groupInterchanges([stn("a", "A", 0, 0, "red"), stn("b", "B", d, 0, "green")]);
    expect(at(INTERCHANGE_RADIUS_BLOCKS)).toHaveLength(1);
    expect(at(INTERCHANGE_RADIUS_BLOCKS + 1)).toHaveLength(2);
  });

  it("chains stations linked through a neighbour (single-link clustering)", () => {
    // a-b and b-c are each 8 apart; a-c is 16. All three are still one place.
    const groups = groupInterchanges([
      stn("a", "A", 0, 0, "red"),
      stn("b", "B", 8, 0, "green"),
      stn("c", "C", 16, 0, "blue"),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.railwayIds).toEqual(["red", "green", "blue"]);
  });

  it("places the interchange at the mean of its members", () => {
    const groups = groupInterchanges([stn("a", "A", 0, 0, "red"), stn("b", "B", 6, 4, "green")]);
    expect(groups[0]!.point).toEqual({ x: 3, z: 2 });
  });

  it("gives a lone station a group of one", () => {
    const groups = groupInterchanges([stn("a", "Lonely", 0, 0, "red")]);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.stations).toHaveLength(1);
  });

  it("ignores non-station features", () => {
    expect(groupInterchanges([railway("red", "#ff0000")])).toEqual([]);
  });

  it("deduplicates railway ids when one line has two stations in the group", () => {
    const groups = groupInterchanges([stn("a", "A", 0, 0, "red"), stn("b", "B", 4, 0, "red")]);
    expect(groups[0]!.railwayIds).toEqual(["red"]);
  });

  it("is stable: the id and member order do not depend on input order", () => {
    const a = stn("a", "Alpha", 0, 0, "red");
    const b = stn("b", "Beta", 5, 0, "green");
    expect(groupInterchanges([a, b])[0]!.id).toBe(groupInterchanges([b, a])[0]!.id);
    expect(groupInterchanges([b, a])[0]!.stations.map((s) => s.id)).toEqual(["a", "b"]);
  });
});

describe("interchange helpers", () => {
  const feats = [stn("a", "Central", 0, 0, "red"), stn("b", "Central", 5, 0, "green"), stn("c", "Far", 99, 99, "red")];

  it("finds the interchange a station belongs to", () => {
    expect(interchangeOf(feats, "a")!.stations.map((s) => s.id)).toEqual(["a", "b"]);
    expect(interchangeOf(feats, "nope")).toBeNull();
  });

  it("lists the other stations at the same interchange", () => {
    expect(interchangePeers(feats, "a").map((s) => s.id)).toEqual(["b"]);
    expect(interchangePeers(feats, "c")).toEqual([]);
  });

  it("collapses a shared name to one label and keeps differing names", () => {
    const shared = groupInterchanges([stn("a", "Central", 0, 0, "red"), stn("b", "Central", 5, 0, "green")])[0]!;
    expect(interchangeLabel(shared, () => "Unnamed")).toBe("Central");
    const differing = groupInterchanges([stn("a", "King St", 0, 0, "red"), stn("b", "Queen St", 5, 0, "green")])[0]!;
    expect(interchangeLabel(differing, () => "Unnamed")).toBe("King St / Queen St");
    const blank = groupInterchanges([stn("a", "  ", 0, 0, "red")])[0]!;
    expect(interchangeLabel(blank, () => "Unnamed station")).toBe("Unnamed station");
  });

  it("maps each served line to its colour, falling back for an unknown railway", () => {
    const g = groupInterchanges([stn("a", "A", 0, 0, "red"), stn("b", "B", 5, 0, "ghost")])[0]!;
    expect(interchangeColours(g, new Map([["red", "#ff0000"]]), "#000000")).toEqual(["#ff0000", "#000000"]);
  });
});
