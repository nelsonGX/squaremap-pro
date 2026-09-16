/**
 * squaremap `tiles/settings.json` and `tiles/<world>/settings.json` shapes, from squaremap v1.3.12
 * `web/src/js/types.ts` (Settings, Settings_World, WorldSettings, WorldSettings_Zoom, ...) and the
 * server writer `common/.../task/UpdateWorldData.java`. Only the fields this app reads are
 * validated; the rest are typed for documentation.
 */

export interface SettingsWorld {
  /** Web name: `Util.levelWebName` = dimension identifier with ":" replaced by "_". */
  name: string;
  display_name: string;
  icon: string;
  type: string;
  order: number;
}

export interface Settings {
  /** Present in types.ts; not written by UpdateWorldData (Squaremap.js reads `json.static || false`). */
  static?: boolean;
  worlds: SettingsWorld[];
  ui: {
    title: string;
    coordinates: { enabled: boolean; html: string };
    link: { enabled: boolean };
    sidebar: { pinned: string; player_list_label: string; world_list_label: string };
  };
}

export interface WorldSettingsZoom {
  max: number;
  def: number;
  extra: number;
}

/**
 * `player_tracker.nameplates` as written by `UpdateWorldData.writeWorldSettings`. Only `show_heads`
 * and `heads_url` are used here (armour/health are not part of `GET /api/players`).
 */
export interface WorldSettingsNameplates {
  enabled?: boolean;
  show_heads?: boolean;
  /** Template with `{uuid}` / `{name}` placeholders, e.g. `https://mc-heads.net/avatar/{uuid}/16`. */
  heads_url?: string;
  show_armor?: boolean;
  show_health?: boolean;
}

export interface WorldSettingsPlayerTracker {
  enabled?: boolean;
  update_interval?: number;
  label?: string;
  nameplates?: WorldSettingsNameplates;
}

export interface WorldSettings {
  spawn: { x: number; z: number };
  zoom: WorldSettingsZoom;
  marker_update_interval: number;
  tiles_update_interval: number;
  player_tracker?: WorldSettingsPlayerTracker;
}

function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

export function isSettings(v: unknown): v is Settings {
  if (!isObj(v) || !Array.isArray(v.worlds)) return false;
  return v.worlds.every(
    (w) =>
      isObj(w) &&
      typeof w.name === "string" &&
      typeof w.display_name === "string" &&
      isNum(w.order),
  );
}

export function isWorldSettings(v: unknown): v is WorldSettings {
  if (!isObj(v) || !isObj(v.spawn) || !isObj(v.zoom)) return false;
  return (
    isNum(v.spawn.x) &&
    isNum(v.spawn.z) &&
    isNum(v.zoom.max) &&
    isNum(v.zoom.def) &&
    isNum(v.zoom.extra)
  );
}

/** squaremap `WorldConfig.PLAYER_TRACKER_NAMEPLATE_HEADS_URL` default (v1.3.12). */
export const DEFAULT_HEADS_URL = "https://mc-heads.net/avatar/{uuid}/16";

/**
 * Used when a world's settings.json cannot be loaded (squaremap WorldConfig defaults: zoom max 3,
 * default 3, extra 2), and always in fixture mode (no tiles).
 */
export const DEFAULT_WORLD_SETTINGS: WorldSettings = {
  spawn: { x: 0, z: 0 },
  zoom: { max: 3, def: 3, extra: 2 },
  marker_update_interval: 5,
  tiles_update_interval: 15,
  player_tracker: { nameplates: { enabled: true, show_heads: true, heads_url: DEFAULT_HEADS_URL } },
};

/**
 * The head-image URL template to use for player markers, or null when the server turned heads off
 * (`player-tracker.nameplate.show-head: false`). squaremap's default points at mc-heads.net, so a
 * viewer with no internet access simply gets no image — the marker falls back to a coloured dot.
 */
export function headsUrlTemplate(settings: WorldSettings): string | null {
  const np = settings.player_tracker?.nameplates;
  if (!np || np.show_heads === false) return null;
  const url = typeof np.heads_url === "string" ? np.heads_url.trim() : "";
  return url === "" ? null : url;
}

/** squaremap `Player.getHeadUrl`: `{uuid}` / `{name}` substitution (URL-encoded here). */
export function playerHeadUrl(template: string, uuid: string, name: string): string {
  return template
    .replace(/\{uuid\}/g, encodeURIComponent(uuid))
    .replace(/\{name\}/g, encodeURIComponent(name));
}

/**
 * Refresh intervals, in ms, from the world settings — the same numbers squaremap's `World.tick`
 * counts ticks against (`tiles_update_interval` = background render interval seconds,
 * `marker_update_interval` = marker API update interval seconds). Clamped to >= 1 s so a
 * misconfigured 0 cannot become a busy loop.
 */
function refreshMs(seconds: unknown, fallback: number): number {
  return Math.max(1, Math.round(isNum(seconds) ? seconds : fallback)) * 1000;
}

export function tilesRefreshMs(settings: WorldSettings): number {
  return refreshMs(settings.tiles_update_interval, DEFAULT_WORLD_SETTINGS.tiles_update_interval);
}

export function markersRefreshMs(settings: WorldSettings): number {
  return refreshMs(settings.marker_update_interval, DEFAULT_WORLD_SETTINGS.marker_update_interval);
}

/** `${tilesBase}/<web name>/settings.json` (squaremap `UpdateWorldData` writes it under `tiles/<web name>/`). */
export function worldSettingsUrl(tilesBase: string, worldId: string): string {
  return `${tilesBase.replace(/\/+$/, "")}/${worldIdToWebName(worldId)}/settings.json`;
}

/** Tile template `${tilesBase}/<web name>/{z}/{x}_{y}.png` (squaremap `LayerControl.createTileLayer`). */
export function worldTileTemplate(tilesBase: string, worldId: string): string {
  return `${tilesBase.replace(/\/+$/, "")}/${worldIdToWebName(worldId)}/{z}/{x}_{y}.png`;
}

/** Worlds sorted by `order`, as squaremap's `WorldList` does (`a[1].order - b[1].order`). */
export function sortWorlds(worlds: readonly SettingsWorld[]): SettingsWorld[] {
  return [...worlds].sort((a, b) => a.order - b.order);
}

/** Route API world id -> squaremap web name (`toString().replace(":", "_")`). */
export function worldIdToWebName(worldId: string): string {
  return worldId.replace(":", "_");
}

/**
 * squaremap web name -> route API world id (`namespace:path`). The server mapping replaces the
 * single ":" with "_", which is not reversible when the namespace itself contains "_". We assume
 * the namespace has no "_" (true for `minecraft`) and replace the first "_".
 */
export function webNameToWorldId(webName: string): string {
  const i = webName.indexOf("_");
  return i < 0 ? webName : `${webName.slice(0, i)}:${webName.slice(i + 1)}`;
}

export type SettingsFetch = (url: string, init: RequestInit) => Promise<Response>;

/**
 * Loads a world's squaremap settings.json. Never rejects except on abort: on any failure it returns
 * {@link DEFAULT_WORLD_SETTINGS} with a warning (the map still works, zoom levels may be off).
 */
export async function loadWorldSettings(
  url: string,
  signal?: AbortSignal,
  fetchImpl: SettingsFetch = (u, i) => fetch(u, i),
): Promise<{ settings: WorldSettings; warning: string | null }> {
  const fallback = (why: string) => ({
    settings: DEFAULT_WORLD_SETTINGS,
    warning: `Could not load squaremap world settings (${why}); using default zoom levels.`,
  });
  try {
    const res = await fetchImpl(url, { cache: "no-store", signal });
    if (!res.ok) return fallback(`HTTP ${res.status}`);
    const json: unknown = await res.json();
    return isWorldSettings(json) ? { settings: json, warning: null } : fallback("unexpected shape");
  } catch (e) {
    if (signal?.aborted) throw e;
    return fallback(e instanceof Error ? e.message : String(e));
  }
}
