import { describe, expect, it } from "vitest";
import { isRoutePayload, routePayloadProblem } from "./routeSchema";
import { classifyRouteResponse } from "./routeClient";

/** Verbatim from CLAUDE.md "Route JSON schema (v1)". */
const EXAMPLE_TEXT = `{
  "schemaVersion": 1,
  "status": "ok",
  "world": "minecraft:overworld",
  "from":  { "x": 12, "y": 64, "z": -40 },
  "to":    { "x": 310, "y": 71, "z": 95 },
  "points": [ { "x": 12, "y": 64, "z": -40 }, { "x": 150, "y": 66, "z": 10 }, { "x": 310, "y": 71, "z": 95 } ],
  "distance": 342.7,
  "nodesExpanded": 18422,
  "error": null
}`;

const example = (): Record<string, unknown> => JSON.parse(EXAMPLE_TEXT) as Record<string, unknown>;

const failure = (status: string): Record<string, unknown> => ({
  schemaVersion: 1,
  status,
  world: "minecraft:overworld",
  from: null,
  to: null,
  points: [],
  distance: 0,
  nodesExpanded: 0,
  error: "detail",
});

describe("routeSchema", () => {
  it("accepts the CLAUDE.md example verbatim", () => {
    expect(routePayloadProblem(example())).toBeNull();
    expect(isRoutePayload(example())).toBe(true);
  });

  it("accepts every non-ok status with empty points", () => {
    for (const s of ["no_path", "cap_exceeded", "invalid_request", "world_not_found", "not_ready"]) {
      expect(routePayloadProblem(failure(s))).toBeNull();
    }
    expect(isRoutePayload({ ...failure("no_path"), from: { x: 1, y: 2, z: 3 }, error: null })).toBe(true);
  });

  it("rejects missing keys", () => {
    for (const key of Object.keys(example())) {
      const v = example();
      delete v[key];
      expect(routePayloadProblem(v)).toBe(`missing key '${key}'`);
    }
  });

  it("rejects wrong types", () => {
    const bad: Array<[string, unknown]> = [
      ["schemaVersion", 2],
      ["schemaVersion", "1"],
      ["world", 5],
      ["from", { x: 1, y: 2 }],
      ["from", { x: 1.5, y: 2, z: 3 }],
      ["to", "310,95"],
      ["points", {}],
      ["points", [{ x: 12, y: 64, z: "-40" }]],
      ["distance", "342.7"],
      ["distance", -1],
      ["nodesExpanded", 1.5],
      ["error", 0],
    ];
    for (const [key, value] of bad) {
      expect(isRoutePayload({ ...example(), [key]: value })).toBe(false);
    }
    expect(isRoutePayload(null)).toBe(false);
    expect(isRoutePayload([])).toBe(false);
    expect(isRoutePayload("ok")).toBe(false);
  });

  it("rejects unknown status", () => {
    expect(routePayloadProblem({ ...example(), status: "OK" })).toBe("unknown status");
    expect(isRoutePayload({ ...failure("timeout") })).toBe(false);
  });

  it("rejects points present on failure", () => {
    expect(isRoutePayload({ ...failure("no_path"), points: [{ x: 1, y: 2, z: 3 }] })).toBe(false);
    expect(isRoutePayload({ ...failure("cap_exceeded"), distance: 5 })).toBe(false);
  });

  it("rejects ok payloads that break first = from / last = to or carry an error", () => {
    expect(isRoutePayload({ ...example(), points: [] })).toBe(false);
    expect(isRoutePayload({ ...example(), from: null })).toBe(false);
    expect(isRoutePayload({ ...example(), to: { x: 310, y: 70, z: 95 } })).toBe(false);
    expect(isRoutePayload({ ...example(), error: "x" })).toBe(false);
  });

  it("classifies responses and checks HTTP status agreement", () => {
    expect(classifyRouteResponse(200, example()).kind).toBe("ok");
    expect(classifyRouteResponse(404, failure("world_not_found")).kind).toBe("world_not_found");
    expect(classifyRouteResponse(503, failure("not_ready")).kind).toBe("not_ready");
    expect(classifyRouteResponse(200, failure("not_ready")).kind).toBe("invalid_payload");
    expect(classifyRouteResponse(502, { error: "bad gateway" }).kind).toBe("invalid_payload");
  });
});
