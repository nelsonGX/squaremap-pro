/**
 * Build-time configuration. `NEXT_PUBLIC_*` values are inlined by Next at build time, so they must be
 * read with the literal `process.env.NEXT_PUBLIC_...` expression.
 *
 * Production (static export served by the mod) uses the defaults: API and tiles on the same origin.
 */

export function cleanBase(v: string | undefined): string | undefined {
  const t = v?.trim();
  return t === undefined || t === "" ? undefined : t.replace(/\/+$/, "");
}

/** Prefix for API calls (`${API_BASE}/api/...`). Default "" = same origin. */
export const API_BASE: string = cleanBase(process.env.NEXT_PUBLIC_API_BASE) ?? "";

/**
 * squaremap tiles directory as served over HTTP (squaremap's `<webDir>/tiles`). Tile images are
 * `${TILES_BASE}/<world web name>/{z}/{x}_{y}.png`, per-world settings `${TILES_BASE}/<world web name>/settings.json`.
 */
export const TILES_BASE: string = cleanBase(process.env.NEXT_PUBLIC_TILES_BASE) ?? "/tiles";

/** `1` = use the in-memory fixture API instead of HTTP (dev without a server). */
export const USE_FIXTURES: boolean = process.env.NEXT_PUBLIC_USE_FIXTURES === "1";

/** Fixture mode only: `0` = the fixture API reports "logged out" (default: logged in as an editor). */
export const FIXTURE_LOGGED_IN_ENABLED: boolean = process.env.NEXT_PUBLIC_FIXTURE_LOGGED_IN !== "0";
