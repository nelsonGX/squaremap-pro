/**
 * Pure helpers for the live player layer fed by `GET /api/players` (positions the server snapshots
 * from squaremap's visibility rules: spectators, invisible players and squaremap-hidden players are
 * already filtered out server-side).
 */
import type { OnlinePlayer } from "../api/types";

/** How often the player layer is refreshed while it is switched on. */
export const PLAYER_POLL_MS = 1000;

/**
 * Screen rotation for a player arrow, in degrees clockwise from "up".
 *
 * Minecraft yaw is 0 facing +z and grows clockwise seen from above; the map draws +z downwards, so
 * a player facing +z points down the screen (180°).
 */
export function screenHeading(yaw: number): number {
  return ((yaw + 180) % 360 + 360) % 360;
}

/** Tooltip/aria text for a player marker. */
export function playerTitle(p: OnlinePlayer): string {
  return `${p.name} · ${p.x}, ${p.y}, ${p.z}`;
}

/** Players of one world, sorted by name (the API already filters, this keeps the list stable). */
export function playersInWorld(players: readonly OnlinePlayer[], worldId: string | null): OnlinePlayer[] {
  if (!worldId) return [];
  return players.filter((p) => p.world === worldId).sort((a, b) => a.name.localeCompare(b.name) || a.uuid.localeCompare(b.uuid));
}

/**
 * A stable colour per player, derived from the UUID. It backs the marker dot, which is what shows
 * when the head image from squaremap's `player-tracker.nameplate.heads-url` cannot load — the
 * default points at mc-heads.net, so a LAN with no internet access falls back to these colours.
 */
export function playerColour(uuid: string): string {
  let h = 0;
  for (let i = 0; i < uuid.length; i++) h = (h * 31 + uuid.charCodeAt(i)) >>> 0;
  return `hsl(${h % 360} 72% 46%)`;
}
