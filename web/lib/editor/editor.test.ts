import { describe, expect, it } from "vitest";
import type { Feature, XZ } from "../api/types";
import { parseAuthQuery } from "./authQuery";
import {
  draftToInput,
  editorReducer,
  INITIAL_EDITOR_STATE,
  isDirty,
  type EditorAction,
  type EditorState,
} from "./draft";
import {
  blocksPerPixel,
  cleanGeometry,
  placeStation,
  positionsToGeometry,
  snapPosition,
  snapTargets,
  toBlockXZ,
} from "./geometry";
import { codePointLength, detailsByField, findSelfIntersection, validateInput } from "./validate";

const meta = {
  revision: 3,
  createdBy: { uuid: "u", name: "Steve" },
  createdAt: "2026-09-15T10:00:00Z",
  updatedBy: { uuid: "u", name: "Steve" },
  updatedAt: "2026-09-15T10:00:00Z",
};
const p = (x: number, z: number): XZ => ({ x, z });
const road: Feature = { ...meta, id: "road", type: "road", name: "Main", geometry: [p(0, 0), p(100, 0)], props: { roadClass: "main" } };
const rail: Feature = { ...meta, id: "rail", type: "railway", name: "Red", geometry: [p(0, 50), p(100, 50)], props: { colour: "#ff0000" } };
const rail2: Feature = { ...meta, id: "rail2", type: "railway", name: "Blue", geometry: [p(100, 50), p(100, 150)], props: { colour: "#0000ff" } };
const building: Feature = {
  ...meta, id: "b", type: "building", name: "", geometry: [p(10, 10), p(20, 10), p(20, 20)], props: { category: "public", description: "d" },
};
const station: Feature = { ...meta, id: "s", type: "station", name: "Central", geometry: [p(0, 50)], props: { railwayId: "rail" } };

const run = (actions: EditorAction[], from: EditorState = INITIAL_EDITOR_STATE) => actions.reduce(editorReducer, from);

describe("rounding and snapping", () => {
  it("rounds positions down to the containing block (negative too)", () => {
    expect(toBlockXZ({ x: 3.99, z: -0.01 })).toEqual({ x: 3, z: -1 });
    expect(toBlockXZ({ x: -0.5, z: 0 })).toEqual({ x: -1, z: 0 });
  });

  it("blocks per pixel: 1 at maxZoom, doubling per zoom-out", () => {
    expect(blocksPerPixel(3, 3)).toBe(1);
    expect(blocksPerPixel(1, 3)).toBe(4);
    expect(blocksPerPixel(5, 3)).toBe(0.25);
  });

  it("snaps to the nearest target block centre within range, else floors", () => {
    const targets = [p(10, 10), p(13, 10)];
    expect(snapPosition({ x: 11.9, z: 10.5 }, targets, 2)).toEqual(p(10, 10));
    expect(snapPosition({ x: 12.2, z: 10.5 }, targets, 2)).toEqual(p(13, 10));
    expect(snapPosition({ x: 50.2, z: 50.7 }, targets, 2)).toEqual(p(50, 50));
  });

  it("snap targets are road + railway vertices, deduplicated, excluding the edited feature", () => {
    const shared: Feature = { ...road, id: "road2", geometry: [p(100, 0), p(100, -50)] };
    expect(snapTargets([road, shared, building, station, rail], "road")).toEqual([p(100, 0), p(100, -50), p(0, 50), p(100, 50)]);
  });

  it("road/railway vertices snap to junctions; buildings only round", () => {
    const targets = snapTargets([road], null);
    expect(positionsToGeometry("road", [{ x: 98.7, z: 1.2 }, { x: 98.2, z: 60.9 }], targets, 3)).toEqual([p(100, 0), p(98, 60)]);
    expect(positionsToGeometry("building", [{ x: 98.7, z: 1.2 }, { x: 5, z: 5 }, { x: 5, z: 9 }], targets, 3)).toEqual([p(98, 1), p(5, 5), p(5, 9)]);
  });

  it("cleans consecutive duplicates and closing vertex of rings", () => {
    expect(cleanGeometry("road", [p(0, 0), p(0, 0), p(1, 0), p(1, 0)])).toEqual([p(0, 0), p(1, 0)]);
    expect(cleanGeometry("building", [p(0, 0), p(5, 0), p(5, 5), p(0, 0)])).toEqual([p(0, 0), p(5, 0), p(5, 5)]);
    expect(cleanGeometry("station", [p(1, 1), p(2, 2)])).toEqual([p(1, 1)]);
  });

  it("places stations only on railway vertices, reporting every railway sharing the vertex", () => {
    const features = [road, rail, rail2];
    expect(placeStation({ x: 99.8, z: 51.1 }, features, 3)).toEqual({ vertex: p(100, 50), railwayIds: ["rail", "rail2"] });
    expect(placeStation({ x: 0.5, z: 0.5 }, features, 3)).toBeNull(); // road vertex does not count
    expect(placeStation({ x: 50.5, z: 50.5 }, features, 3)).toBeNull(); // mid-segment
  });
});

describe("validateInput (mirror of FeatureValidator)", () => {
  const lookup = (id: string) => [rail, rail2].find((r) => r.id === id)?.geometry;
  const fields = (i: Parameters<typeof validateInput>[0]) => validateInput(i, lookup).map((d) => d.field);

  it("names: optional for buildings, required otherwise, max 64 code points", () => {
    expect(fields(draftToInputOf(building))).toEqual([]);
    expect(fields({ ...draftToInputOf(road), name: "  " })).toEqual(["name"]);
    expect(fields({ ...draftToInputOf(road), name: "😀".repeat(64) })).toEqual([]);
    expect(fields({ ...draftToInputOf(road), name: "a".repeat(65) })).toEqual(["name"]);
    expect(codePointLength("😀")).toBe(1);
  });

  it("colour, description and vertex rules", () => {
    expect(fields({ type: "railway", name: "R", geometry: rail.geometry, props: { colour: "red" } })).toEqual(["colour"]);
    expect(fields({ type: "building", name: "", geometry: building.geometry, props: { category: "other", description: "x".repeat(1001) } })).toEqual(["description"]);
    expect(validateInput({ type: "road", name: "R", geometry: [p(0, 0)], props: { roadClass: "path" } }, lookup)[0]!.message).toMatch(/at least 2/);
    expect(validateInput({ type: "road", name: "R", geometry: [p(0, 0), p(0, 0)], props: { roadClass: "path" } }, lookup)[0]!.message).toMatch(/identical/);
    expect(fields({ type: "road", name: "R", geometry: [p(0, 0), p(30_000_001, 0)], props: { roadClass: "path" } })).toEqual(["geometry"]);
  });

  it("building rings: open, simple, non-zero area", () => {
    const b = (geometry: XZ[]) => validateInput({ type: "building", name: "", geometry, props: { category: "other", description: "" } }, lookup);
    expect(b([p(0, 0), p(5, 0), p(5, 5), p(0, 0)])[0]!.message).toMatch(/repeat/);
    expect(b([p(0, 0), p(10, 10), p(10, 0), p(0, 10)])[0]!.message).toMatch(/crosses itself/);
    expect(b([p(0, 0), p(5, 0), p(10, 0)])[0]!.message).toMatch(/overlap|zero area|crosses/);
    expect(b([p(0, 0), p(10, 0), p(10, 10), p(0, 10)])).toEqual([]);
    expect(findSelfIntersection([p(0, 0), p(10, 0), p(10, 10), p(0, 10)])).toBeNull();
    expect(findSelfIntersection([p(0, 0), p(10, 0), p(0, 0 + 5), p(10, 5)])).toMatch(/intersect/);
  });

  it("stations: on a vertex of an existing railway", () => {
    const s = (geometry: XZ[], railwayId: string) => fields({ type: "station", name: "S", geometry, props: { railwayId } });
    expect(s([p(0, 50)], "rail")).toEqual([]);
    expect(s([p(1, 50)], "rail")).toEqual(["geometry"]);
    expect(s([p(0, 50)], "nope")).toEqual(["railwayId"]);
    expect(s([], "")).toEqual(["geometry"]);
  });

  it("groups details by known field", () => {
    const m = detailsByField([{ field: "name", message: "a" }, { field: "props.x", message: "b" }, { field: "name", message: "c" }], ["name"]);
    expect(m.get("name")).toEqual(["a", "c"]);
    expect(m.get("")).toEqual(["b"]);
  });
});

function draftToInputOf(f: Feature) {
  const s = run([{ type: "start_edit", feature: f }]);
  return draftToInput(s.draft!);
}

describe("editorReducer", () => {
  it("create draft starts clean with defaults and becomes dirty on edits", () => {
    const s = run([{ type: "start_create", featureType: "road" }]);
    expect(s.draft).toMatchObject({ type: "road", featureId: null, geometry: [], fields: { roadClass: "street" } });
    expect(isDirty(s.draft)).toBe(false);
    const s2 = editorReducer(s, { type: "set_geometry", geometry: [p(0, 0), p(0, 0), p(5, 0)] });
    expect(s2.draft!.geometry).toEqual([p(0, 0), p(5, 0)]);
    expect(isDirty(s2.draft)).toBe(true);
  });

  it("edit draft copies the feature; irrelevant field changes do not dirty it; revert restores", () => {
    const s = run([{ type: "start_edit", feature: road }]);
    expect(s.draft).toMatchObject({ featureId: "road", revision: 3, fields: { name: "Main", roadClass: "main" } });
    expect(isDirty(editorReducer(s, { type: "set_field", field: "colour", value: "#000000" }).draft)).toBe(false);
    const changed = editorReducer(s, { type: "set_field", field: "name", value: "Main St" });
    expect(isDirty(changed.draft)).toBe(true);
    expect(isDirty(editorReducer(changed, { type: "revert" }).draft)).toBe(false);
  });

  it("each started draft gets a new key", () => {
    const s = run([{ type: "start_edit", feature: road }, { type: "start_edit", feature: road }]);
    expect(s.draft!.key).toBe(2);
  });

  it("place_station sets geometry and railway", () => {
    const s = run([{ type: "start_create", featureType: "station" }, { type: "place_station", vertex: p(100, 50), railwayId: "rail2" }]);
    expect(draftToInput(s.draft!)).toEqual({ type: "station", name: "", geometry: [p(100, 50)], props: { railwayId: "rail2" } });
    expect(run([{ type: "start_create", featureType: "road" }, { type: "place_station", vertex: p(1, 1), railwayId: "r" }]).draft!.geometry).toEqual([]);
  });

  it("save flow: start → ok gives a clean edit draft of the saved feature", () => {
    const s = run([
      { type: "start_create", featureType: "road" },
      { type: "set_field", field: "name", value: "X" },
      { type: "save_start" },
    ]);
    expect(s.phase).toBe("saving");
    expect(editorReducer(s, { type: "set_field", field: "name", value: "ignored" }).draft!.fields.name).toBe("X");
    const ok = editorReducer(s, { type: "save_ok", feature: { ...road, id: "new", revision: 1 }, created: true });
    expect(ok).toMatchObject({ phase: "idle", notice: { kind: "saved", text: "Created" }, draft: { featureId: "new", revision: 1 } });
    expect(isDirty(ok.draft)).toBe(false);
  });

  it("save errors: 409 conflict keeps the draft, 400 keeps server details, others show a message", () => {
    const s = run([{ type: "start_edit", feature: road }, { type: "save_start" }]);
    const conflict = editorReducer(s, { type: "save_error", failure: { status: 409, message: "", details: [], current: { ...road, revision: 4 } } });
    expect(conflict.notice).toMatchObject({ kind: "conflict", current: { revision: 4 } });
    expect(conflict.draft!.featureId).toBe("road");
    const invalid = editorReducer(s, { type: "save_error", failure: { status: 400, message: "m", details: [{ field: "name", message: "bad" }] } });
    expect(invalid.serverDetails).toEqual([{ field: "name", message: "bad" }]);
    const other = editorReducer(s, { type: "save_error", failure: { status: 422, message: "Has stations", details: [] } });
    expect(other.notice).toEqual({ kind: "error", text: "Has stations" });
  });

  it("reload_latest replaces the draft, or closes it when the feature is gone", () => {
    const s = run([{ type: "start_edit", feature: road }, { type: "set_field", field: "name", value: "mine" }]);
    const latest = editorReducer(s, { type: "reload_latest", feature: { ...road, name: "theirs", revision: 9 } });
    expect(latest.draft).toMatchObject({ revision: 9, fields: { name: "theirs" } });
    expect(isDirty(latest.draft)).toBe(false);
    expect(editorReducer(s, { type: "reload_latest", feature: null }).draft).toBeNull();
  });

  it("delete requires confirmation and an existing feature", () => {
    expect(run([{ type: "start_create", featureType: "road" }, { type: "ask_delete" }]).phase).toBe("idle");
    const s = run([{ type: "start_edit", feature: road }, { type: "delete_start" }]);
    expect(s.phase).toBe("idle");
    const confirm = run([{ type: "ask_delete" }], s);
    expect(confirm.phase).toBe("confirm_delete");
    expect(editorReducer(confirm, { type: "cancel_delete" }).phase).toBe("idle");
    const deleted = run([{ type: "delete_start" }, { type: "delete_ok" }], confirm);
    expect(deleted).toMatchObject({ draft: null, phase: "idle", notice: { kind: "deleted" } });
  });

  it("draftToInput trims names, lower-cases colours and keeps only the type's props", () => {
    const s = run([
      { type: "start_edit", feature: rail },
      { type: "set_field", field: "name", value: "  Red Line " },
      { type: "set_field", field: "colour", value: "#AA00FF" },
      { type: "set_field", field: "description", value: "ignored" },
    ]);
    expect(draftToInput(s.draft!)).toEqual({ type: "railway", name: "Red Line", geometry: rail.geometry, props: { colour: "#aa00ff" } });
    expect(draftToInputOf(building)).toEqual({ type: "building", name: "", geometry: building.geometry, props: { category: "public", description: "d" } });
  });
});

describe("parseAuthQuery", () => {
  it("reads edit=1 and strips it, keeping other params", () => {
    expect(parseAuthQuery("?edit=1&world=minecraft:overworld")).toMatchObject({ edit: true, authError: null, changed: true, cleanedSearch: "?world=minecraft%3Aoverworld" });
    expect(parseAuthQuery("?edit=1")).toMatchObject({ edit: true, cleanedSearch: "" });
  });

  it("maps authError=expired to the /mapedit hint; unknown codes get a generic message", () => {
    expect(parseAuthQuery("?authError=expired").authError).toBe("Login link expired — run /mapedit again.");
    expect(parseAuthQuery("?authError=weird").authError).toMatch(/mapedit/);
  });

  it("no params → unchanged", () => {
    expect(parseAuthQuery("")).toEqual({ edit: false, authError: null, cleanedSearch: "", changed: false });
    expect(parseAuthQuery("?edit=0").edit).toBe(false);
  });
});

describe("saveFailureOf", () => {
  it("maps ApiError kinds and statuses to UI messages, keeping details and current", async () => {
    const { ApiError } = await import("../api/client");
    const { saveFailureOf, isAuthFailure, SESSION_LOST_MESSAGE } = await import("./errors");
    expect(saveFailureOf(new ApiError("network", "x"))).toMatchObject({ status: 0, message: expect.stringMatching(/reach the server/) });
    expect(saveFailureOf(new ApiError("http", "x", 401))).toMatchObject({ status: 401, message: SESSION_LOST_MESSAGE });
    expect(isAuthFailure(403)).toBe(true);
    expect(isAuthFailure(409)).toBe(false);
    const details = [{ field: "name", message: "bad" }];
    expect(saveFailureOf(new ApiError("http", "x", 400, "validation", details))).toMatchObject({ details });
    expect(saveFailureOf(new ApiError("http", "x", 409, "conflict", [], road))).toMatchObject({ status: 409, current: road });
    expect(saveFailureOf(new ApiError("http", "x", 422, "railway_has_stations")).message).toMatch(/stations/);
    expect(saveFailureOf(new Error("boom"))).toEqual({ status: 0, message: "boom", details: [] });
  });
});

describe("draftStyle", () => {
  it("follows unsaved fields", async () => {
    const { draftStyle } = await import("./draft");
    const s = run([{ type: "start_edit", feature: rail }, { type: "set_field", field: "colour", value: "#00ff00" }]);
    expect(draftStyle(s.draft!, new Map())).toMatchObject({ shape: "line", main: { color: "#00ff00" } });
    const bad = editorReducer(s, { type: "set_field", field: "colour", value: "#0f" });
    expect(draftStyle(bad.draft!, new Map()).shape === "line" && (draftStyle(bad.draft!, new Map()) as { main: { color: string } }).main.color).toBe("#374151");
    const st = run([{ type: "start_edit", feature: station }]);
    expect(draftStyle(st.draft!, new Map([["rail", "#ff0000"]]))).toMatchObject({ shape: "circle", main: { color: "#ff0000" } });
  });
});
