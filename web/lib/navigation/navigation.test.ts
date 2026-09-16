import { describe, expect, it } from "vitest";
import { ApiError, HttpApiClient, type FetchLike } from "../api/client";
import { parseRouteResponse } from "../api/guards";
import { FixtureApiClient } from "../api/fixtures";
import type { Feature, RouteLeg, RouteResponse, XZ } from "../api/types";
import { coordParam, featureAnchor, formatCoord, parseCoordInput, ringCentroidBlock } from "./coords";
import { formatBlocks, formatClock } from "./format";
import {
  httpStatusForRoute,
  legLineStyle,
  legSteps,
  legView,
  modesParam,
  RAIL_FALLBACK_COLOUR,
  ROAD_LEG_COLOUR,
  routePath,
  routePoints,
  routeStatusMessage,
  routeSummary,
} from "./legs";
import { readNavUrlState, writeNavUrlState } from "./urlState";

const p = (x: number, z: number): XZ => ({ x, z });
const meta = {
  revision: 1,
  createdBy: { uuid: "u", name: "S" },
  createdAt: "t",
  updatedBy: { uuid: "u", name: "S" },
  updatedAt: "t",
};
const road: Feature = { ...meta, id: "r", type: "road", name: "Main", geometry: [p(0, 0), p(10, 0), p(20, 0)], props: { roadClass: "main" } };
const rail: Feature = { ...meta, id: "rw", type: "railway", name: "Red Line", geometry: [p(0, 50), p(90, 50)], props: { colour: "#d62828" } };
const station: Feature = { ...meta, id: "s", type: "station", name: "Central", geometry: [p(0, 50)], props: { railwayId: "rw" } };
const building: Feature = {
  ...meta, id: "b", type: "building", name: "Hall", geometry: [p(0, 0), p(10, 0), p(10, 10), p(0, 10)], props: { category: "public", description: "" },
};

describe("coordinates", () => {
  it("parses x, z in several spellings", () => {
    expect(parseCoordInput("12, -40")).toEqual(p(12, -40));
    expect(parseCoordInput("  12,-40 ")).toEqual(p(12, -40));
    expect(parseCoordInput("12 -40")).toEqual(p(12, -40));
    expect(parseCoordInput("+5, -0")).toEqual(p(5, 0));
    expect(Object.is(parseCoordInput("-0, 0")!.x, -0)).toBe(false);
  });

  it("rejects non-integers and junk", () => {
    for (const bad of ["", "12", "1.5, 2", "a, b", "1,2,3", "99999999999999999999, 1"]) expect(parseCoordInput(bad)).toBeNull();
  });

  it("formats for inputs and query strings", () => {
    expect(formatCoord(p(3, -4))).toBe("3, -4");
    expect(coordParam(p(3, -4))).toBe("3,-4");
  });

  it("feature anchors: building centroid, station point, nearest / middle vertex", () => {
    expect(ringCentroidBlock(building.geometry)).toEqual(p(5, 5));
    expect(featureAnchor(building)).toEqual(p(5, 5));
    expect(featureAnchor(station)).toEqual(p(0, 50));
    expect(featureAnchor(road)).toEqual(p(10, 0));
    expect(featureAnchor(road, p(19, 7))).toEqual(p(20, 0));
    expect(ringCentroidBlock([p(0, 0), p(4, 0), p(8, 0)])).toEqual(p(4, 0)); // degenerate: vertex average
  });
});

describe("formatting", () => {
  it("formats durations as m:ss under an hour and h:mm from an hour", () => {
    expect(formatClock(0)).toBe("0:00 min");
    expect(formatClock(59.6)).toBe("1:00 min");
    expect(formatClock(245)).toBe("4:05 min");
    expect(formatClock(3599)).toBe("59:59 min");
    expect(formatClock(3600)).toBe("1:00 h");
    expect(formatClock(3725)).toBe("1:02 h");
    expect(formatClock(-5)).toBe("0:00 min");
  });

  it("formats block distances", () => {
    expect(formatBlocks(1)).toBe("1 block");
    expect(formatBlocks(316.8)).toBe("317 blocks");
    expect(formatBlocks(1234567)).toBe("1,234,567 blocks");
  });
});

describe("URL state", () => {
  it("reads world/from/to, ignoring malformed values", () => {
    expect(readNavUrlState("?world=minecraft:overworld&from=12,-40&to=310,95")).toEqual({
      world: "minecraft:overworld",
      from: p(12, -40),
      to: p(310, 95),
    });
    expect(readNavUrlState("?from=nope&to=1,2")).toEqual({ world: null, from: null, to: p(1, 2) });
  });

  it("writes readable params and keeps unrelated ones", () => {
    expect(writeNavUrlState("?edit=1", { world: "minecraft:overworld", from: p(1, -2), to: null })).toBe(
      "?edit=1&world=minecraft:overworld&from=1,-2",
    );
    expect(writeNavUrlState("?world=a:b&from=1,2&to=3,4&x=y", { world: "a:b", from: null, to: null })).toBe("?x=y");
    expect(writeNavUrlState("", { world: "a:b", from: null, to: null })).toBe("");
  });

  it("round-trips", () => {
    const state = { world: "minecraft:the_nether", from: p(-7, 8), to: p(9, -10) };
    expect(readNavUrlState(writeNavUrlState("", state))).toEqual(state);
  });
});

describe("route request helpers", () => {
  it("modes: omitted when all enabled, walk always included otherwise", () => {
    expect(modesParam({ road: true, rail: true })).toBeUndefined();
    expect(modesParam({ road: true, rail: false })).toEqual(["walk", "road"]);
    expect(modesParam({ road: false, rail: false })).toEqual(["walk"]);
  });

  it("builds the route path", () => {
    expect(routePath({ world: "minecraft:overworld", from: p(12, -40), to: p(310, 95), modes: ["walk", "rail"] })).toBe(
      "/api/route?world=minecraft:overworld&from=12,-40&to=310,95&modes=walk,rail",
    );
  });

  it("maps statuses to HTTP", () => {
    expect([httpStatusForRoute("ok"), httpStatusForRoute("no_path"), httpStatusForRoute("invalid_request"), httpStatusForRoute("world_not_found")]).toEqual([200, 200, 400, 404]);
  });
});

const legs: RouteLeg[] = [
  { mode: "walk", name: null, points: [p(12, -40), p(20, -38)], distance: 8.2, duration: 1.9 },
  { mode: "road", name: "Main Street", points: [p(20, -38), p(20, 10), p(150, 10)], distance: 178, duration: 24.8 },
  { mode: "walk", name: null, points: [p(150, 10), p(150, 20)], distance: 10, duration: 2.3 },
  { mode: "rail", name: "Red Line", points: [p(150, 20), p(310, 95)], fromStation: "Central", toStation: "Harbour", distance: 170, duration: 21.3 },
  { mode: "walk", name: null, points: [p(310, 95), p(310, 100)], distance: 5, duration: 1.2 },
];
const route: RouteResponse = {
  schemaVersion: 2, status: "ok", world: "w", from: p(12, -40), to: p(310, 100), legs, distance: 371.2, duration: 51.5, error: null,
};

describe("leg presentation", () => {
  it("titles legs by mode and next leg", () => {
    expect(legs.map((_, i) => legView(legs[i]!, i, legs).title)).toEqual([
      "Walk to Main Street",
      "Follow Main Street",
      "Walk to Central station",
      "Take Red Line",
      "Walk to destination",
    ]);
    expect(legView(legs[3]!, 3, legs).detail).toBe("Board at Central → alight at Harbour");
    expect(legView(legs[1]!, 1, legs).meta).toBe("0:25 min · 178 blocks");
  });

  it("turn-by-turn per walk/road leg; arrive only on the last leg; none for rail", () => {
    expect(legSteps(legs[1]!, false).map((s) => s.text)).toEqual(["Head south", "Turn left"]);
    expect(legSteps(legs[4]!, true).map((s) => s.kind)).toEqual(["depart", "arrive"]);
    expect(legSteps(legs[3]!, false)).toEqual([]);
    const zero: RouteLeg = { mode: "walk", name: null, points: [p(1, 1), p(1, 1)], distance: 0, duration: 0 };
    expect(legSteps(zero, true)).toEqual([]);
    expect(legView(zero, 0, [zero]).title).toBe("You are already there");
  });

  it("summarises modes and non-ok statuses", () => {
    expect(routeSummary(route)).toBe("Walk · Main Street · Walk · Red Line · Walk");
    expect(routeStatusMessage(route)).toBeNull();
    expect(routeStatusMessage({ ...route, status: "no_path", legs: [] })).toMatch(/No route/);
    expect(routeStatusMessage({ ...route, status: "invalid_request", error: "bad from" })).toMatch(/bad from/);
  });

  it("styles: walk dotted grey, road blue, rail in railway colour (fallback by name)", () => {
    expect(legLineStyle(legs[0]!, []).dashArray).toBeTruthy();
    expect(legLineStyle(legs[1]!, []).color).toBe(ROAD_LEG_COLOUR);
    expect(legLineStyle(legs[3]!, [rail]).color).toBe("#d62828");
    expect(legLineStyle({ ...legs[3]!, name: "Unknown" }, [rail]).color).toBe(RAIL_FALLBACK_COLOUR);
  });

  it("collects all route points for bounds", () => {
    expect(routePoints(route)).toHaveLength(2 + legs.reduce((n, l) => n + l.points.length, 0));
  });
});

function fakeFetch(status: number, body: unknown): { fetch: FetchLike; urls: string[] } {
  const urls: string[] = [];
  return {
    urls,
    fetch: async (url) => {
      urls.push(url);
      return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
    },
  };
}

describe("HttpApiClient.route", () => {
  const req = { world: "minecraft:overworld", from: p(12, -40), to: p(310, 100) };

  it("returns ok, no_path, invalid_request (400) and world_not_found (404) bodies", async () => {
    const f = fakeFetch(200, route);
    await expect(new HttpApiClient("", f.fetch).route(req)).resolves.toMatchObject({ status: "ok" });
    expect(f.urls[0]).toBe("/api/route?world=minecraft:overworld&from=12,-40&to=310,100");
    const bad = { ...route, status: "invalid_request", legs: [], distance: 0, duration: 0, from: null, to: null, error: "x" };
    await expect(new HttpApiClient("", fakeFetch(400, bad).fetch).route(req)).resolves.toMatchObject({ status: "invalid_request" });
    const nf = { ...bad, status: "world_not_found" };
    await expect(new HttpApiClient("", fakeFetch(404, nf).fetch).route(req)).resolves.toMatchObject({ status: "world_not_found" });
  });

  it("rejects status mismatches, malformed bodies and other errors", async () => {
    await expect(new HttpApiClient("", fakeFetch(400, route).fetch).route(req)).rejects.toMatchObject({ kind: "invalid_response" });
    await expect(new HttpApiClient("", fakeFetch(200, { schemaVersion: 1 }).fetch).route(req)).rejects.toMatchObject({ kind: "invalid_response" });
    const err = await new HttpApiClient("", fakeFetch(500, { error: "internal" }).fetch).route(req).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ kind: "http", status: 500, code: "internal" });
    await expect(new HttpApiClient("", fakeFetch(502, "<html>").fetch).route(req)).rejects.toMatchObject({ kind: "http", status: 502 });
  });
});

describe("FixtureApiClient.route", () => {
  const W = "minecraft:overworld";
  const c = new FixtureApiClient();
  const valid = (r: RouteResponse) => {
    const parsed = parseRouteResponse(JSON.parse(JSON.stringify(r)));
    expect(parsed.ok ? null : parsed.problem).toBeNull();
  };

  it("special inputs: world_not_found, invalid_request, no_path, same point", async () => {
    const nf = await c.route({ world: "minecraft:the_end", from: p(0, 0), to: p(1, 1) });
    expect(nf.status).toBe("world_not_found");
    const bad = await c.route({ world: W, from: p(40_000_000, 0), to: p(1, 1) });
    expect(bad.status).toBe("invalid_request");
    const none = await c.route({ world: W, from: p(0, 0), to: p(13, 13) });
    expect(none.status).toBe("no_path");
    const here = await c.route({ world: W, from: p(5, 5), to: p(5, 5) });
    expect(here.legs).toEqual([{ mode: "walk", name: null, points: [p(5, 5), p(5, 5)], distance: 0, duration: 0 }]);
    for (const r of [nf, bad, none, here]) valid(r);
  });

  it("uses rail between fixture stations for a long north-south trip, and respects modes", async () => {
    // Near North Park (50,-250) to near Foundry (60,150) on the Blue Line.
    const r = await c.route({ world: W, from: p(45, -260), to: p(70, 160) });
    valid(r);
    expect(r.legs.map((l) => l.mode)).toEqual(["walk", "rail", "walk"]);
    expect(r.legs[1]).toMatchObject({ name: "Blue Line", fromStation: "North Park", toStation: "Foundry" });
    const noRail = await c.route({ world: W, from: p(45, -260), to: p(70, 160), modes: ["walk", "road"] });
    valid(noRail);
    expect(noRail.legs.some((l) => l.mode === "rail")).toBe(false);
  });

  it("uses a road when it is faster than walking", async () => {
    const r = await c.route({ world: W, from: p(-300, -62), to: p(300, -62), modes: ["walk", "road"] });
    valid(r);
    expect(r.legs.find((l) => l.mode === "road")?.name).toBe("King's Highway");
  });
});
