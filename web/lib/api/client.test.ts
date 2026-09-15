import { describe, expect, it } from "vitest";
import { ApiError, buildRequestInit, HttpApiClient, joinUrl, worldSegment, type FetchLike } from "./client";
import { FIXTURE_FEATURES, FIXTURE_PLAYER, FixtureApiClient } from "./fixtures";
import type { Feature, FeatureInput } from "./types";

function fakeFetch(status: number, body: string): { fetch: FetchLike; calls: { url: string; init: RequestInit }[] } {
  const calls: { url: string; init: RequestInit }[] = [];
  return {
    calls,
    fetch: async (url, init) => {
      calls.push({ url, init });
      return new Response(status === 204 ? null : body, { status });
    },
  };
}

describe("request building", () => {
  it("keeps ':' readable in world path segments and encodes the rest", () => {
    expect(worldSegment("minecraft:overworld")).toBe("minecraft:overworld");
    expect(worldSegment("my mod:a/b")).toBe("my%20mod:a%2Fb");
  });

  it("joins base and path without double slashes", () => {
    expect(joinUrl("", "/api/worlds")).toBe("/api/worlds");
    expect(joinUrl("http://localhost:8080/", "/api/worlds")).toBe("http://localhost:8080/api/worlds");
  });

  it("sends same-origin credentials; CSRF header only on non-GET", () => {
    const get = buildRequestInit("GET");
    expect(get.credentials).toBe("same-origin");
    expect(get.headers).not.toHaveProperty("X-Requested-With");
    const post = buildRequestInit("post", { a: 1 });
    expect(post.method).toBe("POST");
    expect(post.headers).toMatchObject({ "X-Requested-With": "squaremap-pro", "Content-Type": "application/json" });
    expect(post.body).toBe('{"a":1}');
    expect(buildRequestInit("DELETE").headers).toHaveProperty("X-Requested-With", "squaremap-pro");
  });
});

describe("HttpApiClient", () => {
  it("GETs worlds from the base URL and validates them", async () => {
    const f = fakeFetch(200, '[{"id":"minecraft:overworld","name":"world"}]');
    const c = new HttpApiClient("http://h:1", f.fetch);
    await expect(c.listWorlds()).resolves.toEqual([{ id: "minecraft:overworld", name: "world" }]);
    expect(f.calls[0]!.url).toBe("http://h:1/api/worlds");
    expect(f.calls[0]!.init.method).toBe("GET");
  });

  it("GETs features for a world", async () => {
    const f = fakeFetch(200, JSON.stringify({ schemaVersion: 1, features: FIXTURE_FEATURES["minecraft:the_nether"] }));
    const r = await new HttpApiClient("", f.fetch).listFeatures("minecraft:the_nether");
    expect(f.calls[0]!.url).toBe("/api/worlds/minecraft:the_nether/features");
    expect(r.features).toHaveLength(1);
  });

  it("maps HTTP errors with details", async () => {
    const f = fakeFetch(400, '{"error":"validation","details":[{"field":"name","message":"too long"}]}');
    const err = await new HttpApiClient("", f.fetch).me().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ kind: "http", status: 400, code: "validation", details: [{ field: "name", message: "too long" }] });
  });

  it("maps a non-JSON error body to an http error without a code", async () => {
    const err = await new HttpApiClient("", fakeFetch(502, "<html>").fetch).listWorlds().catch((e: unknown) => e);
    expect(err).toMatchObject({ kind: "http", status: 502, code: null });
  });

  it("rejects invalid 200 responses", async () => {
    const err = await new HttpApiClient("", fakeFetch(200, '{"worlds":[]}').fetch).listWorlds().catch((e: unknown) => e);
    expect(err).toMatchObject({ kind: "invalid_response" });
    const notJson = await new HttpApiClient("", fakeFetch(200, "hello").fetch).listWorlds().catch((e: unknown) => e);
    expect(notJson).toMatchObject({ kind: "invalid_response" });
  });

  it("maps network failures", async () => {
    const c = new HttpApiClient("", async () => {
      throw new TypeError("Failed to fetch");
    });
    await expect(c.listWorlds()).rejects.toMatchObject({ kind: "network", status: 0 });
  });

  it("rethrows aborts unchanged", async () => {
    const ac = new AbortController();
    ac.abort();
    const abortErr = new DOMException("aborted", "AbortError");
    const c = new HttpApiClient("", async () => {
      throw abortErr;
    });
    await expect(c.listWorlds(ac.signal)).rejects.toBe(abortErr);
  });
});

describe("FixtureApiClient", () => {
  it("serves worlds and a logged-in editor by default", async () => {
    const c = new FixtureApiClient();
    const worlds = await c.listWorlds();
    expect(worlds.map((w) => w.id)).toEqual(["minecraft:overworld", "minecraft:the_nether"]);
    await expect(c.me()).resolves.toEqual({ loggedIn: true, ...FIXTURE_PLAYER, canEdit: true });
    await expect(new FixtureApiClient({ auth: { loggedIn: false } }).me()).resolves.toEqual({ loggedIn: false });
  });

  it("fixture features are all schema-valid and cover every type", async () => {
    const c = new FixtureApiClient();
    for (const w of await c.listWorlds()) {
      const list = await c.listFeatures(w.id);
      expect(list.skipped).toEqual([]);
      expect(list.features).toHaveLength(FIXTURE_FEATURES[w.id]!.length);
    }
    const over = await c.listFeatures("minecraft:overworld");
    expect(new Set(over.features.map((f) => f.type))).toEqual(new Set(["building", "road", "railway", "station"]));
  });

  it("stations sit on a vertex of their railway", async () => {
    const { features } = await new FixtureApiClient().listFeatures("minecraft:overworld");
    for (const s of features) {
      if (s.type !== "station") continue;
      const rail = features.find((f) => f.id === s.props.railwayId);
      expect(rail?.type).toBe("railway");
      expect(rail!.geometry).toContainEqual(s.geometry[0]);
    }
  });

  it("returns copies and 404s unknown worlds", async () => {
    const c = new FixtureApiClient();
    const a = await c.listFeatures("minecraft:overworld");
    a.features[0]!.name = "changed";
    const b = await c.listFeatures("minecraft:overworld");
    expect(b.features[0]!.name).not.toBe("changed");
    await expect(c.listFeatures("minecraft:the_end")).rejects.toMatchObject({ kind: "http", status: 404 });
  });
});

describe("HttpApiClient writes", () => {
  const input: FeatureInput = { type: "road", name: "A", geometry: [{ x: 0, z: 0 }, { x: 1, z: 0 }], props: { roadClass: "path" } };
  const feature = { ...input, id: "f 1", revision: 2, createdBy: { uuid: "u", name: "S" }, createdAt: "t", updatedBy: { uuid: "u", name: "S" }, updatedAt: "t" };

  it("POSTs a feature with the CSRF header and parses the 201 body", async () => {
    const f = fakeFetch(201, JSON.stringify(feature));
    await expect(new HttpApiClient("", f.fetch).createFeature("minecraft:overworld", input)).resolves.toMatchObject({ id: "f 1" });
    expect(f.calls[0]!.url).toBe("/api/worlds/minecraft:overworld/features");
    expect(f.calls[0]!.init).toMatchObject({ method: "POST", body: JSON.stringify(input), headers: { "X-Requested-With": "squaremap-pro" } });
  });

  it("PUTs with revision, DELETEs with ?revision=, POSTs logout", async () => {
    const f = fakeFetch(200, JSON.stringify(feature));
    const c = new HttpApiClient("", f.fetch);
    await c.updateFeature("w:x", "f 1", { ...input, revision: 2 });
    expect(f.calls[0]!.url).toBe("/api/worlds/w:x/features/f%201");
    expect(JSON.parse(f.calls[0]!.init.body as string)).toMatchObject({ revision: 2 });
    const d = fakeFetch(204, "");
    await expect(new HttpApiClient("", d.fetch).deleteFeature("w:x", "f1", 7)).resolves.toBeUndefined();
    expect(d.calls[0]!.url).toBe("/api/worlds/w:x/features/f1?revision=7");
    expect(d.calls[0]!.init.method).toBe("DELETE");
    const l = fakeFetch(204, "");
    await new HttpApiClient("", l.fetch).logout();
    expect(l.calls[0]!).toMatchObject({ url: "/api/auth/logout", init: { method: "POST" } });
  });

  it("carries a 409 body's current feature on the error", async () => {
    const f = fakeFetch(409, JSON.stringify({ error: "conflict", details: [], current: feature }));
    const err = await new HttpApiClient("", f.fetch).updateFeature("w:x", "f 1", { ...input, revision: 1 }).catch((e: unknown) => e);
    expect(err).toMatchObject({ status: 409, code: "conflict" });
    expect((err as ApiError).current?.revision).toBe(2);
  });
});

describe("FixtureApiClient writes", () => {
  const W = "minecraft:overworld";
  const road: FeatureInput = { type: "road", name: " New Road ", geometry: [{ x: 0, z: -60 }, { x: 0, z: -200 }], props: { roadClass: "main" } };
  const clock = () => new Date("2026-09-15T12:00:00Z");

  it("creates with revision 1, trimmed name and the editor as author", async () => {
    const c = new FixtureApiClient({ now: clock });
    const f = await c.createFeature(W, road);
    expect(f).toMatchObject({ revision: 1, name: "New Road", createdBy: FIXTURE_PLAYER, updatedAt: "2026-09-15T12:00:00.000Z" });
    expect((await c.listFeatures(W)).features.some((x) => x.id === f.id)).toBe(true);
  });

  it("updates bump the revision; stale revisions get 409 with the current feature", async () => {
    const c = new FixtureApiClient();
    const f = await c.createFeature(W, road);
    const updated = await c.updateFeature(W, f.id, { ...road, name: "Renamed", revision: 1 });
    expect(updated).toMatchObject({ revision: 2, name: "Renamed", createdAt: f.createdAt });
    const err = await c.updateFeature(W, f.id, { ...road, revision: 1 }).catch((e: unknown) => e);
    expect(err).toMatchObject({ status: 409 });
    expect((err as ApiError).current?.name).toBe("Renamed");
    await expect(c.deleteFeature(W, f.id, 1)).rejects.toMatchObject({ status: 409 });
    await expect(c.deleteFeature(W, f.id, 2)).resolves.toBeUndefined();
    await expect(c.deleteFeature(W, f.id, 2)).rejects.toMatchObject({ status: 404 });
  });

  it("rejects invalid input with 400 details and type changes", async () => {
    const c = new FixtureApiClient();
    const err = await c.createFeature(W, { ...road, name: "", geometry: [{ x: 1, z: 1 }] }).catch((e: unknown) => e);
    expect(err).toMatchObject({ status: 400, code: "validation" });
    expect((err as ApiError).details.map((d) => d.field).sort()).toEqual(["geometry", "name"]);
    const existing = (await c.listFeatures(W)).features.find((x) => x.id === "f_main_street") as Feature;
    await expect(
      c.updateFeature(W, existing.id, { type: "railway", name: "x", geometry: existing.geometry, props: { colour: "#000000" }, revision: existing.revision }),
    ).rejects.toMatchObject({ status: 400 });
  });

  it("stations must sit on a vertex of their railway", async () => {
    const c = new FixtureApiClient();
    const off = await c
      .createFeature(W, { type: "station", name: "S", geometry: [{ x: 1, z: -100 }], props: { railwayId: "f_red_line" } })
      .catch((e: unknown) => e);
    expect(off).toMatchObject({ status: 400 });
    await expect(
      c.createFeature(W, { type: "station", name: "S", geometry: [{ x: 250, z: -120 }], props: { railwayId: "f_red_line" } }),
    ).resolves.toMatchObject({ type: "station" });
  });

  it("refuses to delete a railway with stations (422)", async () => {
    const c = new FixtureApiClient();
    await expect(c.deleteFeature(W, "f_red_line", 1)).rejects.toMatchObject({ status: 422 });
  });

  it("requires a session (401), edit permission (403); logout ends the session", async () => {
    await expect(new FixtureApiClient({ auth: { loggedIn: false } }).createFeature(W, road)).rejects.toMatchObject({ status: 401 });
    const viewer = new FixtureApiClient({ auth: { loggedIn: true, uuid: "u", name: "V", canEdit: false } });
    await expect(viewer.createFeature(W, road)).rejects.toMatchObject({ status: 403 });
    const c = new FixtureApiClient();
    await c.logout();
    await expect(c.me()).resolves.toEqual({ loggedIn: false });
    await expect(c.createFeature(W, road)).rejects.toMatchObject({ status: 401 });
  });
});
