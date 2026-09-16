import { describe, expect, it } from "vitest";
import {
  parseApiErrorBody,
  parseAuthMe,
  parseFeature,
  parseFeatureList,
  parsePlayerList,
  parseRouteResponse,
  parseWorlds,
} from "./guards";

const who = { uuid: "u1", name: "Steve" };
const meta = { createdBy: who, createdAt: "2026-09-15T10:00:00Z", updatedBy: who, updatedAt: "2026-09-15T11:00:00Z" };

const road = {
  id: "f_1",
  type: "road",
  revision: 3,
  name: "Main Street",
  geometry: [
    { x: 12, z: -40 },
    { x: 150, z: 10 },
  ],
  props: { roadClass: "street" },
  ...meta,
};

const problem = (r: { ok: boolean; problem?: string }) => (r.ok ? null : r.problem);

describe("parseWorlds", () => {
  it("accepts the documented shape", () => {
    expect(parseWorlds([{ id: "minecraft:overworld", name: "world" }])).toEqual({
      ok: true,
      value: [{ id: "minecraft:overworld", name: "world" }],
    });
  });
  it("rejects non-arrays and bad entries", () => {
    expect(parseWorlds({}).ok).toBe(false);
    expect(parseWorlds([{ id: "", name: "x" }]).ok).toBe(false);
    expect(parseWorlds([{ id: "a" }]).ok).toBe(false);
  });
});

describe("parseAuthMe", () => {
  it("logged out drops extra keys", () => {
    expect(parseAuthMe({ loggedIn: false, name: "x" })).toEqual({ ok: true, value: { loggedIn: false } });
  });
  it("logged in requires uuid, name, canEdit", () => {
    expect(parseAuthMe({ loggedIn: true, uuid: "u", name: "Steve", canEdit: true }).ok).toBe(true);
    expect(parseAuthMe({ loggedIn: true, uuid: "u", name: "Steve" }).ok).toBe(false);
    expect(parseAuthMe({}).ok).toBe(false);
  });
});

describe("parseFeature", () => {
  it("accepts the CLAUDE.md example road", () => {
    const r = parseFeature(road);
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.value).toEqual(road);
  });

  it("accepts each type with valid props", () => {
    const tri = [
      { x: 0, z: 0 },
      { x: 5, z: 0 },
      { x: 0, z: 5 },
    ];
    expect(problem(parseFeature({ ...road, type: "building", geometry: tri, props: { category: "public", description: "d" } }))).toBeNull();
    expect(problem(parseFeature({ ...road, type: "railway", props: { colour: "#AABBCC" } }))).toBeNull();
    expect(problem(parseFeature({ ...road, type: "station", geometry: [{ x: 1, z: 2 }], props: { railwayId: "f_r" } }))).toBeNull();
  });

  it("normalises railway colour to lower case and missing description to ''", () => {
    const rw = parseFeature({ ...road, type: "railway", props: { colour: "#AABBCC" } });
    expect(rw.ok && rw.value.type === "railway" && rw.value.props.colour).toBe("#aabbcc");
    const b = parseFeature({
      ...road,
      type: "building",
      geometry: [{ x: 0, z: 0 }, { x: 5, z: 0 }, { x: 0, z: 5 }],
      props: { category: "other" },
    });
    expect(b.ok && b.value.type === "building" && b.value.props.description).toBe("");
  });

  it("rejects malformed features", () => {
    expect(problem(parseFeature({ ...road, type: "bridge" }))).toMatch(/unknown type/);
    expect(problem(parseFeature({ ...road, geometry: [{ x: 1, z: 2 }] }))).toMatch(/>= 2/);
    expect(problem(parseFeature({ ...road, geometry: [{ x: 1.5, z: 2 }, { x: 3, z: 4 }] }))).toMatch(/geometry/);
    expect(problem(parseFeature({ ...road, props: { roadClass: "motorway" } }))).toMatch(/roadClass/);
    expect(problem(parseFeature({ ...road, type: "railway", props: { colour: "red" } }))).toMatch(/colour/);
    expect(problem(parseFeature({ ...road, type: "station", props: { railwayId: "r" } }))).toMatch(/exactly 1/);
    expect(problem(parseFeature({ ...road, revision: -1 }))).toMatch(/revision/);
    expect(problem(parseFeature({ ...road, updatedBy: null }))).toMatch(/updatedBy/);
    const closed = [{ x: 0, z: 0 }, { x: 5, z: 0 }, { x: 0, z: 5 }, { x: 0, z: 0 }];
    expect(problem(parseFeature({ ...road, type: "building", geometry: closed, props: { category: "public" } }))).toMatch(/repeat/);
  });
});

describe("parseFeatureList", () => {
  it("skips invalid and duplicate entries instead of failing the list", () => {
    const r = parseFeatureList({ schemaVersion: 1, features: [road, { ...road, id: "f_2", props: {} }, road] });
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.value.features.map((f) => f.id)).toEqual(["f_1"]);
    expect(r.value.skipped.map((s) => s.index)).toEqual([1, 2]);
  });
  it("rejects a wrong envelope", () => {
    expect(parseFeatureList({ schemaVersion: 2, features: [] }).ok).toBe(false);
    expect(parseFeatureList({ schemaVersion: 1 }).ok).toBe(false);
    expect(parseFeatureList([]).ok).toBe(false);
  });
});

describe("parseApiErrorBody", () => {
  it("reads error and valid details only", () => {
    expect(
      parseApiErrorBody({ error: "validation", details: [{ field: "geometry", message: "too short" }, { field: 1 }] }),
    ).toEqual({ error: "validation", details: [{ field: "geometry", message: "too short" }], current: null });
    expect(parseApiErrorBody({ error: "conflict" })).toEqual({ error: "conflict", details: [], current: null });
    expect(parseApiErrorBody({ error: "conflict", current: road })?.current?.id).toBe("f_1");
    expect(parseApiErrorBody({ error: "conflict", current: { id: 1 } })?.current).toBeNull();
    expect(parseApiErrorBody("nope")).toBeNull();
  });
});

describe("parseRouteResponse (schema v2)", () => {
  const ok = {
    schemaVersion: 2,
    status: "ok",
    world: "minecraft:overworld",
    from: { x: 12, z: -40 },
    to: { x: 310, z: 95 },
    legs: [
      { mode: "walk", name: null, points: [{ x: 12, z: -40 }, { x: 20, z: -38 }], distance: 8.2, duration: 1.9 },
      { mode: "road", name: "Main Street", points: [{ x: 20, z: -38 }, { x: 150, z: 10 }], distance: 138.6, duration: 24.8 },
      {
        mode: "rail", name: "Red Line", points: [{ x: 150, z: 10 }, { x: 310, z: 95 }],
        fromStation: "Central", toStation: "Harbour", distance: 170, duration: 21.3,
      },
    ],
    distance: 316.8,
    duration: 48,
    error: null,
  };

  it("accepts the documented example", () => {
    expect(problem(parseRouteResponse(ok))).toBeNull();
  });

  it("accepts no_path with empty legs", () => {
    expect(problem(parseRouteResponse({ ...ok, status: "no_path", legs: [], distance: 0, duration: 0, error: "x" }))).toBeNull();
  });

  it("rejects broken invariants", () => {
    expect(problem(parseRouteResponse({ ...ok, schemaVersion: 1 }))).toMatch(/schemaVersion/);
    const { duration: _d, ...noDuration } = ok;
    void _d;
    expect(problem(parseRouteResponse(noDuration))).toMatch(/duration/);
    expect(problem(parseRouteResponse({ ...ok, from: { x: 0, z: 0 } }))).toMatch(/start at from/);
    expect(problem(parseRouteResponse({ ...ok, to: { x: 0, z: 0 } }))).toMatch(/end at to/);
    const gap = structuredClone(ok);
    gap.legs[1]!.points[0] = { x: 21, z: -38 };
    expect(problem(parseRouteResponse(gap))).toMatch(/does not start where/);
    const rail = structuredClone(ok) as typeof ok & { legs: Record<string, unknown>[] };
    delete rail.legs[2]!.fromStation;
    expect(problem(parseRouteResponse(rail))).toMatch(/fromStation/);
    expect(problem(parseRouteResponse({ ...ok, status: "no_path" }))).toMatch(/legs present/);
    expect(problem(parseRouteResponse({ ...ok, status: "teleport" }))).toMatch(/status/);
  });
});

describe("parsePlayerList", () => {
  const steve = { uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5", name: "Steve", world: "minecraft:overworld", x: 1, y: 64, z: -2, yaw: 90 };

  it("accepts a well-formed list", () => {
    const r = parsePlayerList({ players: [steve], max: 20 });
    expect(r.ok && r.value).toEqual({ players: [steve], max: 20 });
  });

  it("drops malformed entries instead of failing", () => {
    const r = parsePlayerList({ players: [steve, { uuid: "x" }, { ...steve, x: 1.5 }], max: 20 });
    expect(r.ok && r.value.players).toEqual([steve]);
  });

  it("defaults a missing or negative max to 0", () => {
    const missing = parsePlayerList({ players: [] });
    expect(missing.ok && missing.value.max).toBe(0);
    const neg = parsePlayerList({ players: [], max: -3 });
    expect(neg.ok && neg.value.max).toBe(0);
  });

  it("rejects a body that is not {players: []}", () => {
    expect(problem(parsePlayerList({ max: 1 }))).toMatch(/players/);
    expect(problem(parsePlayerList([]))).toMatch(/players/);
  });
});
