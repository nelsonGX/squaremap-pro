import { API_BASE, USE_FIXTURES } from "../config";
import { HttpApiClient, type ApiClient } from "./client";
import { FixtureApiClient } from "./fixtures";

export * from "./client";
export * from "./guards";
export * from "./types";
export { FixtureApiClient } from "./fixtures";

/** The app's client: fixtures when `NEXT_PUBLIC_USE_FIXTURES=1`, else HTTP against `NEXT_PUBLIC_API_BASE`. */
export function createApiClient(): ApiClient {
  return USE_FIXTURES ? new FixtureApiClient({ delayMs: 150 }) : new HttpApiClient(API_BASE);
}
