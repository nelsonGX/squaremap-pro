import { describe, expect, it } from "vitest";
import { MOCK_WORLD_WEB_NAMES, mockGroundY, mockRoute, polylineLength } from "./mockRoute";
import { routePayloadProblem } from "./routeSchema";
import { buildRouteUrl, parseCoordInput } from "./routeClient";

const q = (from: string | null, to: string | null, world: string | null = null) => ({ from, to, world });

describe("mockRoute validation (400 invalid_request)", () => {
  const invalid: Array<[string, ReturnType<typeof q>]> = [
    ["missing from", q(null, "1,2")],
    ["missing to", q("1,2", null)],
    ["empty from", q("", "1,2")],
    ["spaces", q("1, 2", "3,4")],
    ["float", q("1.5,2", "3,4")],
    ["three parts", q("1,2,3", "3,4")],
    ["plus sign", q("+1,2", "3,4")],
    ["out of range", q("30000001,0", "3,4")],
    ["out of range negative", q("0,-30000001", "3,4")],
    ["overflow", q("99999999999999999999,0", "3,4")],
    ["bad world (no colon)", q("1,2", "3,4", "overworld")],
    ["bad world (uppercase)", q("1,2", "3,4", "Minecraft:overworld")],
    ["empty world", q("1,2", "3,4", "")],
  ];
  for (const [name, query] of invalid) {
    it(name, () => {
      const r = mockRoute(query);
      expect(r.httpStatus).toBe(400);
      expect(r.body.status).toBe("invalid_request");
      expect(r.body.points).toEqual([]);
      expect(r.body.from).toBeNull();
      expect(typeof r.body.error).toBe("string");
      expect(routePayloadProblem(r.body)).toBeNull();
    });
  }

  it("accepts the ±30,000,000 boundary and leading zeros", () => {
    expect(mockRoute(q("30000000,-30000000", "-30000000,30000000")).httpStatus).toBe(200);
    expect(mockRoute(q("-0007,0", "0010,-0")).body.from).toMatchObject({ x: -7, z: 0 });
  });

  it("defaults world to minecraft:overworld and echoes a valid world on 400", () => {
    expect(mockRoute(q("1,2", "3,4")).body.world).toBe("minecraft:overworld");
    expect(mockRoute(q("x", "3,4", "minecraft:the_end")).body.world).toBe("minecraft:the_end");
    expect(mockRoute(q("1,2", "3,4", "BAD")).body.world).toBe("minecraft:overworld");
  });
});

describe("mockRoute special cases", () => {
  it("unknown world -> 404 world_not_found", () => {
    const r = mockRoute(q("1,2", "3,4", "custom:dimension"));
    expect(r.httpStatus).toBe(404);
    expect(r.body.status).toBe("world_not_found");
    expect(routePayloadProblem(r.body)).toBeNull();
  });

  it("known worlds come from the mock settings.json", () => {
    expect(MOCK_WORLD_WEB_NAMES).toEqual(["minecraft_overworld", "minecraft_the_nether", "minecraft_the_end"]);
    expect(mockRoute(q("1,2", "3,4", "minecraft:the_nether")).httpStatus).toBe(200);
  });

  it("to.x == 13 -> 200 no_path", () => {
    const r = mockRoute(q("0,0", "13,99"));
    expect(r.httpStatus).toBe(200);
    expect(r.body).toMatchObject({ status: "no_path", points: [], distance: 0 });
    expect(r.body.nodesExpanded).toBeGreaterThan(0);
    expect(routePayloadProblem(r.body)).toBeNull();
  });

  it("to.x == 14 -> 200 cap_exceeded", () => {
    const r = mockRoute(q("0,0", "14,-5"));
    expect(r.httpStatus).toBe(200);
    expect(r.body.status).toBe("cap_exceeded");
    expect(routePayloadProblem(r.body)).toBeNull();
  });

  it("to.x == 15 -> 503 not_ready", () => {
    const r = mockRoute(q("0,0", "15,0"));
    expect(r.httpStatus).toBe(503);
    expect(r.body).toMatchObject({ status: "not_ready", from: null, to: null, nodesExpanded: 0 });
    expect(routePayloadProblem(r.body)).toBeNull();
  });

  it("validation takes precedence over special cases", () => {
    expect(mockRoute(q("0,0", "15,0", "Nope")).httpStatus).toBe(400);
  });
});

describe("mockRoute ok payloads", () => {
  const pairs: Array<[string, string]> = [
    ["12,-40", "310,95"],
    ["0,0", "1,0"],
    ["-500,-500", "500,500"],
    ["1000,-2000", "-3000,4000"],
    ["7,7", "7,7"],
    ["-30000000,0", "30000000,5"],
  ];
  for (const [from, to] of pairs) {
    it(`${from} -> ${to}`, () => {
      const r = mockRoute(q(from, to));
      expect(r.httpStatus).toBe(200);
      const b = r.body;
      expect(b.status).toBe("ok");
      expect(routePayloadProblem(b)).toBeNull();
      const [fx, fz] = from.split(",").map(Number) as [number, number];
      const [tx, tz] = to.split(",").map(Number) as [number, number];
      expect(b.from).toEqual({ x: fx, y: mockGroundY(fx, fz), z: fz });
      expect(b.to).toEqual({ x: tx, y: mockGroundY(tx, tz), z: tz });
      expect(b.points[0]).toEqual(b.from);
      expect(b.points[b.points.length - 1]).toEqual(b.to);
      expect(b.distance).toBeCloseTo(polylineLength(b.points), 9);
      expect(b.nodesExpanded).toBeGreaterThan(0);
      for (const pt of b.points) {
        expect(pt.y).toBe(mockGroundY(pt.x, pt.z));
        expect(Math.abs(pt.x)).toBeLessThanOrEqual(30_000_000);
        expect(Math.abs(pt.z)).toBeLessThanOrEqual(30_000_000);
      }
    });
  }

  it("has several bends and some y changes on a long route, deterministically", () => {
    const a = mockRoute(q("12,-40", "310,95")).body;
    const b = mockRoute(q("12,-40", "310,95")).body;
    expect(a).toEqual(b);
    expect(a.points.length).toBeGreaterThanOrEqual(4);
    expect(new Set(a.points.map((pt) => pt.y)).size).toBeGreaterThan(1);
  });
});

describe("route client helpers", () => {
  it("parses x, z input", () => {
    expect(parseCoordInput("12, -40")).toEqual({ x: 12, z: -40 });
    expect(parseCoordInput(" -3,4 ")).toEqual({ x: -3, z: 4 });
    expect(parseCoordInput("5 6")).toEqual({ x: 5, z: 6 });
    expect(parseCoordInput("1.5, 2")).toBeNull();
    expect(parseCoordInput("abc")).toBeNull();
    expect(parseCoordInput("")).toBeNull();
  });

  it("builds the route URL", () => {
    expect(buildRouteUrl("http://127.0.0.1:8765/", { x: 12, z: -40 }, { x: 310, z: 95 }, "minecraft:overworld")).toBe(
      "http://127.0.0.1:8765/route?from=12,-40&to=310,95&world=minecraft:overworld",
    );
    expect(buildRouteUrl("/api/mock", { x: 0, z: 0 }, { x: 1, z: 1 }, "minecraft:the_end")).toBe(
      "/api/mock/route?from=0,0&to=1,1&world=minecraft:the_end",
    );
  });
});
