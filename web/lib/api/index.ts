import { API_BASE, FIXTURE_LOGGED_IN_ENABLED, USE_FIXTURES } from "../config";
import { HttpApiClient, type ApiClient } from "./client";
import { FIXTURE_LOGGED_IN, FixtureApiClient } from "./fixtures";

export * from "./client";
export * from "./guards";
export * from "./types";
export { FIXTURE_LOGGED_IN, FIXTURE_PLAYER, FixtureApiClient } from "./fixtures";

/** The app's client: fixtures when `NEXT_PUBLIC_USE_FIXTURES=1`, else HTTP against `NEXT_PUBLIC_API_BASE`. */
export function createApiClient(): ApiClient {
  if (!USE_FIXTURES) return new HttpApiClient(API_BASE);
  return new FixtureApiClient({ delayMs: 150, auth: FIXTURE_LOGGED_IN_ENABLED ? FIXTURE_LOGGED_IN : { loggedIn: false } });
}
