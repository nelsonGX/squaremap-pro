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

export interface WorldSettings {
  spawn: { x: number; z: number };
  zoom: WorldSettingsZoom;
  marker_update_interval: number;
  tiles_update_interval: number;
  player_tracker?: unknown;
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
