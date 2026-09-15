import { describe, expect, it } from "vitest";
import { ApiError, buildRequestInit, HttpApiClient, joinUrl, worldSegment, type FetchLike } from "./client";
import { FIXTURE_FEATURES, FixtureApiClient } from "./fixtures";

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
  it("serves worlds, features and a logged-out user", async () => {
    const c = new FixtureApiClient();
    const worlds = await c.listWorlds();
    expect(worlds.map((w) => w.id)).toEqual(["minecraft:overworld", "minecraft:the_nether"]);
    await expect(c.me()).resolves.toEqual({ loggedIn: false });
    await expect(new FixtureApiClient({ auth: { loggedIn: true, uuid: "u", name: "S", canEdit: true } }).me()).resolves.toMatchObject({ canEdit: true });
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
