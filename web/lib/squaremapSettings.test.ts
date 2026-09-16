import { describe, expect, it } from "vitest";
import {
  DEFAULT_HEADS_URL,
  DEFAULT_WORLD_SETTINGS,
  headsUrlTemplate,
  isWorldSettings,
  loadWorldSettings,
  markersRefreshMs,
  playerHeadUrl,
  tilesRefreshMs,
  worldIdToWebName,
  worldSettingsUrl,
  worldTileTemplate,
} from "./squaremapSettings";

describe("squaremap tile paths", () => {
  it("maps world ids to squaremap web names", () => {
    expect(worldIdToWebName("minecraft:the_nether")).toBe("minecraft_the_nether");
  });

  it("builds settings and tile URLs under the tiles base", () => {
    expect(worldSettingsUrl("/tiles", "minecraft:overworld")).toBe("/tiles/minecraft_overworld/settings.json");
    expect(worldTileTemplate("http://h:8080/tiles/", "minecraft:the_end")).toBe(
      "http://h:8080/tiles/minecraft_the_end/{z}/{x}_{y}.png",
    );
  });

  it("default world settings are valid", () => {
    expect(isWorldSettings(DEFAULT_WORLD_SETTINGS)).toBe(true);
    expect(isWorldSettings({ spawn: { x: 0, z: 0 }, zoom: { max: 3 } })).toBe(false);
  });
});

describe("loadWorldSettings", () => {
  const settings = { spawn: { x: 5, z: -7 }, zoom: { max: 4, def: 2, extra: 1 }, marker_update_interval: 5, tiles_update_interval: 15 };

  it("returns parsed settings", async () => {
    const r = await loadWorldSettings("/tiles/w/settings.json", undefined, async () => new Response(JSON.stringify(settings)));
    expect(r).toEqual({ settings, warning: null });
  });

  it("falls back to defaults with a warning on HTTP error, bad shape or network error", async () => {
    const http = await loadWorldSettings("u", undefined, async () => new Response("", { status: 404 }));
    expect(http.settings).toBe(DEFAULT_WORLD_SETTINGS);
    expect(http.warning).toMatch(/HTTP 404/);
    const shape = await loadWorldSettings("u", undefined, async () => new Response("{}"));
    expect(shape.warning).toMatch(/unexpected shape/);
    const net = await loadWorldSettings("u", undefined, async () => {
      throw new TypeError("offline");
    });
    expect(net.warning).toMatch(/offline/);
  });
});

describe("player head URLs", () => {
  it("uses the world's configured heads_url", () => {
    expect(headsUrlTemplate(DEFAULT_WORLD_SETTINGS)).toBe(DEFAULT_HEADS_URL);
    expect(
      headsUrlTemplate({ ...DEFAULT_WORLD_SETTINGS, player_tracker: { nameplates: { show_heads: true, heads_url: "/heads/{name}.png" } } }),
    ).toBe("/heads/{name}.png");
  });

  it("is null when heads are off, blank or absent", () => {
    const off = { ...DEFAULT_WORLD_SETTINGS, player_tracker: { nameplates: { show_heads: false, heads_url: DEFAULT_HEADS_URL } } };
    expect(headsUrlTemplate(off)).toBeNull();
    expect(headsUrlTemplate({ ...DEFAULT_WORLD_SETTINGS, player_tracker: { nameplates: { show_heads: true, heads_url: "  " } } })).toBeNull();
    expect(headsUrlTemplate({ ...DEFAULT_WORLD_SETTINGS, player_tracker: undefined })).toBeNull();
  });

  it("substitutes {uuid} and {name}, URL-encoded", () => {
    expect(playerHeadUrl(DEFAULT_HEADS_URL, "abc-def", "Steve")).toBe("https://mc-heads.net/avatar/abc-def/16");
    expect(playerHeadUrl("/h/{name}/{name}.png", "u", "a b")).toBe("/h/a%20b/a%20b.png");
  });
});

describe("refresh intervals", () => {
  it("converts the squaremap intervals to milliseconds", () => {
    const s = { ...DEFAULT_WORLD_SETTINGS, marker_update_interval: 5, tiles_update_interval: 15 };
    expect(markersRefreshMs(s)).toBe(5000);
    expect(tilesRefreshMs(s)).toBe(15000);
  });

  it("never returns less than a second, and falls back when the field is missing", () => {
    expect(tilesRefreshMs({ ...DEFAULT_WORLD_SETTINGS, tiles_update_interval: 0 })).toBe(1000);
    expect(markersRefreshMs({ ...DEFAULT_WORLD_SETTINGS, marker_update_interval: undefined as unknown as number })).toBe(5000);
  });
});
