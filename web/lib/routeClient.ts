/**
 * Route API client helpers: URL building, coordinate input parsing and response classification.
 * Pure except `fetchRoute`.
 */
import { routePayloadProblem, type RoutePayload } from "./routeSchema";

/** UI state of a route request. */
export type RouteResult =
  | { kind: "ok"; payload: RoutePayload }
  | { kind: "no_path"; payload: RoutePayload }
  | { kind: "cap_exceeded"; payload: RoutePayload }
  | { kind: "invalid_request"; payload: RoutePayload }
  | { kind: "world_not_found"; payload: RoutePayload }
  | { kind: "not_ready"; payload: RoutePayload }
  | { kind: "network_error"; message: string }
  | { kind: "invalid_payload"; message: string };

/** Parses "x, z" / "x,z" / "x z" into integer block coordinates, or null. */
export function parseCoordInput(raw: string): { x: number; z: number } | null {
  const m = /^\s*(-?\d+)\s*(?:,\s*|\s+)(-?\d+)\s*$/.exec(raw);
  if (!m || m[1] === undefined || m[2] === undefined) return null;
  const x = Number(m[1]);
  const z = Number(m[2]);
  if (!Number.isSafeInteger(x) || !Number.isSafeInteger(z)) return null;
  return { x: x + 0, z: z + 0 };
}

export function formatCoord(p: { x: number; z: number }): string {
  return `${p.x}, ${p.z}`;
}

/** `${base}/route?from=x,z&to=x,z&world=ns:path` — base has no trailing slash requirement. */
export function buildRouteUrl(
  base: string,
  from: { x: number; z: number },
  to: { x: number; z: number },
  world: string,
): string {
  const params = new URLSearchParams({
    from: `${from.x},${from.z}`,
    to: `${to.x},${to.z}`,
    world,
  });
  // Keep "," and ":" readable; both are legal in a query string.
  const qs = params.toString().replace(/%2C/gi, ",").replace(/%3A/gi, ":");
  return `${base.replace(/\/+$/, "")}/route?${qs}`;
}

/**
 * Classifies a parsed JSON body (any HTTP status) into a UI state. The HTTP status must agree with
 * the payload status (schema v1 mapping), otherwise the payload is treated as invalid.
 */
export function classifyRouteResponse(httpStatus: number, body: unknown): RouteResult {
  const problem = routePayloadProblem(body);
  if (problem !== null) {
    return { kind: "invalid_payload", message: `Unexpected response (HTTP ${httpStatus}): ${problem}` };
  }
  const payload = body as RoutePayload;
  const expected: Record<RoutePayload["status"], number> = {
    ok: 200,
    no_path: 200,
    cap_exceeded: 200,
    invalid_request: 400,
    world_not_found: 404,
    not_ready: 503,
  };
  if (expected[payload.status] !== httpStatus) {
    return {
      kind: "invalid_payload",
      message: `HTTP ${httpStatus} does not match status '${payload.status}'`,
    };
  }
  return { kind: payload.status, payload } as RouteResult;
}

export async function fetchRoute(url: string, signal: AbortSignal): Promise<RouteResult> {
  let res: Response;
  try {
    res = await fetch(url, { signal, cache: "no-store", headers: { Accept: "application/json" } });
  } catch (e) {
    if (signal.aborted) throw e;
    return { kind: "network_error", message: e instanceof Error ? e.message : String(e) };
  }
  let body: unknown;
  try {
    body = await res.json();
  } catch (e) {
    if (signal.aborted) throw e;
    return { kind: "invalid_payload", message: `HTTP ${res.status}: response is not JSON` };
  }
  return classifyRouteResponse(res.status, body);
}
