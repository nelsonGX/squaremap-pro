/** Shareable directions state in the query string: `?world=<id>&from=x,z&to=x,z`. Pure. */
import type { XZ } from "../api/types";
import { coordParam, parseCoordInput } from "./coords";

export interface NavUrlState {
  world: string | null;
  from: XZ | null;
  to: XZ | null;
}

function parseParam(v: string | null): XZ | null {
  return v === null ? null : parseCoordInput(v.replace(",", ", "));
}

export function readNavUrlState(search: string): NavUrlState {
  const params = new URLSearchParams(search);
  const world = params.get("world");
  return { world: world && world.trim() !== "" ? world : null, from: parseParam(params.get("from")), to: parseParam(params.get("to")) };
}

/**
 * New search string with `world`/`from`/`to` set from `state` (a null field removes its parameter;
 * `world` is only written together with an endpoint). Other parameters (e.g. `edit`) are kept in
 * order. `:` and `,` stay readable.
 */
export function writeNavUrlState(search: string, state: NavUrlState): string {
  const params = new URLSearchParams(search);
  const hasEndpoint = state.from !== null || state.to !== null;
  const set = (k: string, v: string | null) => (v === null ? params.delete(k) : params.set(k, v));
  set("world", hasEndpoint ? state.world : null);
  set("from", state.from ? coordParam(state.from) : null);
  set("to", state.to ? coordParam(state.to) : null);
  const qs = params.toString().replace(/%2C/gi, ",").replace(/%3A/gi, ":");
  return qs === "" ? "" : `?${qs}`;
}
