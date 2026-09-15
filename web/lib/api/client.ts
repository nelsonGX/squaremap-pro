/**
 * API client interface + HTTP implementation. Every method resolves with a validated value or
 * rejects with an {@link ApiError}. Aborts reject with the fetch AbortError unchanged.
 */
import {
  parseApiErrorBody,
  parseAuthMe,
  parseFeature,
  parseFeatureList,
  parseWorlds,
  type Parsed,
  type ParsedFeatureList,
} from "./guards";
import type { AuthMe, Feature, FeatureInput, FeatureUpdate, ValidationDetail, World } from "./types";

export interface ApiClient {
  /** `GET /api/worlds` */
  listWorlds(signal?: AbortSignal): Promise<World[]>;
  /** `GET /api/worlds/{world}/features` */
  listFeatures(worldId: string, signal?: AbortSignal): Promise<ParsedFeatureList>;
  /** `GET /api/auth/me` */
  me(signal?: AbortSignal): Promise<AuthMe>;
  /** `POST /api/auth/logout` → 204 */
  logout(): Promise<void>;
  /** `POST /api/worlds/{world}/features` → 201 Feature */
  createFeature(worldId: string, input: FeatureInput): Promise<Feature>;
  /** `PUT /api/worlds/{world}/features/{id}` → 200 Feature · 409 stale revision */
  updateFeature(worldId: string, id: string, update: FeatureUpdate): Promise<Feature>;
  /** `DELETE /api/worlds/{world}/features/{id}?revision=` → 204 · 409 stale · 422 railway has stations */
  deleteFeature(worldId: string, id: string, revision: number): Promise<void>;
}

export type ApiErrorKind = "http" | "network" | "invalid_response";

export class ApiError extends Error {
  constructor(
    readonly kind: ApiErrorKind,
    message: string,
    /** HTTP status, 0 for network errors. */
    readonly status: number = 0,
    /** Server `error` code (e.g. `validation`) when the body had one. */
    readonly code: string | null = null,
    readonly details: ValidationDetail[] = [],
    /** Latest stored feature, when a 409 body carries one. */
    readonly current: Feature | null = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** CSRF header required by the server on every write (CLAUDE.md "Auth"). */
export const CSRF_HEADER = "X-Requested-With";
export const CSRF_VALUE = "squaremap-pro";

/**
 * Path segment for a world id. `:` is legal in a path segment, so it is kept readable
 * (`/api/worlds/minecraft:overworld/features`); everything else is percent-encoded.
 */
export function worldSegment(worldId: string): string {
  return encodeURIComponent(worldId).replace(/%3A/gi, ":");
}

export function featurePath(worldId: string, id: string): string {
  return `/api/worlds/${worldSegment(worldId)}/features/${encodeURIComponent(id)}`;
}

export function joinUrl(base: string, path: string): string {
  return `${base.replace(/\/+$/, "")}${path}`;
}

/** fetch init for an API request. Non-GET requests carry the CSRF header. */
export function buildRequestInit(method: string, body?: unknown, signal?: AbortSignal): RequestInit {
  const headers: Record<string, string> = { Accept: "application/json" };
  const upper = method.toUpperCase();
  if (upper !== "GET" && upper !== "HEAD") headers[CSRF_HEADER] = CSRF_VALUE;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const init: RequestInit = { method: upper, credentials: "same-origin", cache: "no-store", headers };
  if (body !== undefined) init.body = JSON.stringify(body);
  if (signal) init.signal = signal;
  return init;
}

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export class HttpApiClient implements ApiClient {
  private readonly fetchImpl: FetchLike;

  constructor(
    /** Prefix for `/api/...`; "" = same origin. */
    private readonly base: string,
    fetchImpl?: FetchLike,
  ) {
    this.fetchImpl = fetchImpl ?? ((input, init) => fetch(input, init));
  }

  listWorlds(signal?: AbortSignal): Promise<World[]> {
    return this.request("GET", "/api/worlds", undefined, signal, parseWorlds);
  }

  listFeatures(worldId: string, signal?: AbortSignal): Promise<ParsedFeatureList> {
    return this.request("GET", `/api/worlds/${worldSegment(worldId)}/features`, undefined, signal, parseFeatureList);
  }

  me(signal?: AbortSignal): Promise<AuthMe> {
    return this.request("GET", "/api/auth/me", undefined, signal, parseAuthMe);
  }

  logout(): Promise<void> {
    return this.request("POST", "/api/auth/logout", undefined, undefined, null);
  }

  createFeature(worldId: string, input: FeatureInput): Promise<Feature> {
    return this.request("POST", `/api/worlds/${worldSegment(worldId)}/features`, input, undefined, parseFeature);
  }

  updateFeature(worldId: string, id: string, update: FeatureUpdate): Promise<Feature> {
    return this.request("PUT", featurePath(worldId, id), update, undefined, parseFeature);
  }

  deleteFeature(worldId: string, id: string, revision: number): Promise<void> {
    return this.request("DELETE", `${featurePath(worldId, id)}?revision=${revision}`, undefined, undefined, null);
  }

  /** Shared request path: network errors, non-2xx (with error body), JSON + shape validation. */
  protected async request<T>(
    method: string,
    path: string,
    body: unknown,
    signal: AbortSignal | undefined,
    parse: ((v: unknown) => Parsed<T>) | null,
  ): Promise<T> {
    const url = joinUrl(this.base, path);
    let res: Response;
    try {
      res = await this.fetchImpl(url, buildRequestInit(method, body, signal));
    } catch (e) {
      if (signal?.aborted) throw e;
      throw new ApiError("network", `Could not reach the server (${e instanceof Error ? e.message : String(e)})`);
    }
    let json: unknown = undefined;
    const text = res.status === 204 ? "" : await res.text();
    if (text !== "") {
      try {
        json = JSON.parse(text);
      } catch {
        if (res.ok) throw new ApiError("invalid_response", `${method} ${path}: response is not JSON`, res.status);
      }
    }
    if (!res.ok) {
      const err = parseApiErrorBody(json);
      const message = err
        ? `${method} ${path} failed (HTTP ${res.status}: ${err.error})`
        : `${method} ${path} failed (HTTP ${res.status})`;
      throw new ApiError("http", message, res.status, err?.error ?? null, err?.details ?? [], err?.current ?? null);
    }
    if (parse === null) return undefined as T;
    const parsed = parse(json);
    if (!parsed.ok) throw new ApiError("invalid_response", `${method} ${path}: ${parsed.problem}`, res.status);
    return parsed.value;
  }
}
