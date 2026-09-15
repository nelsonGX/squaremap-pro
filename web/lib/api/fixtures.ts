/**
 * In-memory fixture API for `NEXT_PUBLIC_USE_FIXTURES=1` (dev without a server) and unit tests.
 * Not an App Router route (static export forbids those). Responses are passed through the same
 * guards as HTTP responses, so fixture data cannot drift from the schema unnoticed.
 */
import { ApiError, type ApiClient } from "./client";
import { parseAuthMe, parseFeatureList, parseWorlds, type ParsedFeatureList } from "./guards";
import type { AuthMe, World } from "./types";

const STEVE = { uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5", name: "Steve" };
const ALEX = { uuid: "ec70bcaf-702f-4bb8-b48d-276fa52a780c", name: "Alex" };

const stamp = (createdAt: string, updatedAt = createdAt, by = STEVE, upd = by) => ({
  createdBy: by,
  createdAt,
  updatedBy: upd,
  updatedAt,
});

const p = (x: number, z: number) => ({ x, z });

export const FIXTURE_WORLDS: World[] = [
  { id: "minecraft:overworld", name: "world" },
  { id: "minecraft:the_nether", name: "world_nether" },
];

/** Raw JSON-shaped features, keyed by world id. */
export const FIXTURE_FEATURES: Record<string, unknown[]> = {
  "minecraft:overworld": [
    {
      id: "f_townhall", type: "building", revision: 2, name: "Town Hall",
      geometry: [p(-60, -45), p(-20, -45), p(-20, -15), p(-60, -15)],
      props: { category: "public", description: "Seat of the town council. Open to all visitors." },
      ...stamp("2026-09-10T09:00:00Z", "2026-09-12T14:30:00Z", STEVE, ALEX),
    },
    {
      id: "f_market", type: "building", revision: 1, name: "Central Market",
      geometry: [p(20, -45), p(70, -45), p(70, -15), p(20, -15)],
      props: { category: "commercial", description: "Food, tools and enchanted books." },
      ...stamp("2026-09-10T10:00:00Z"),
    },
    {
      id: "f_oakrow_houses", type: "building", revision: 1, name: "Oak Row Houses",
      geometry: [p(-90, 30), p(-40, 30), p(-40, 60), p(-65, 75), p(-90, 60)],
      props: { category: "residential", description: "" },
      ...stamp("2026-09-11T08:15:00Z", "2026-09-11T08:15:00Z", ALEX),
    },
    {
      id: "f_ironworks", type: "building", revision: 3, name: "Iron Works",
      geometry: [p(120, 60), p(200, 60), p(200, 120), p(120, 120)],
      props: { category: "industrial", description: "Iron golem farm and smeltery." },
      ...stamp("2026-09-09T18:00:00Z", "2026-09-14T20:05:00Z", ALEX, STEVE),
    },
    {
      id: "f_lighthouse", type: "building", revision: 1, name: "Old Lighthouse",
      geometry: [p(-200, -150), p(-190, -150), p(-190, -140), p(-200, -140)],
      props: { category: "other", description: "Abandoned since the great creeper incident." },
      ...stamp("2026-09-08T12:00:00Z"),
    },
    {
      id: "f_kings_highway", type: "road", revision: 1, name: "King's Highway",
      geometry: [p(-300, -60), p(0, -60), p(300, -60)],
      props: { roadClass: "highway" },
      ...stamp("2026-09-08T12:00:00Z"),
    },
    {
      id: "f_main_street", type: "road", revision: 2, name: "Main Street",
      geometry: [p(0, -60), p(0, 20), p(0, 90), p(0, 150)],
      props: { roadClass: "main" },
      ...stamp("2026-09-08T12:10:00Z", "2026-09-13T07:45:00Z", STEVE, ALEX),
    },
    {
      id: "f_oak_row", type: "road", revision: 1, name: "Oak Row",
      geometry: [p(0, 20), p(-100, 20)],
      props: { roadClass: "street" },
      ...stamp("2026-09-11T08:00:00Z", "2026-09-11T08:00:00Z", ALEX),
    },
    {
      id: "f_foundry_road", type: "road", revision: 1, name: "Foundry Road",
      geometry: [p(0, 90), p(110, 90)],
      props: { roadClass: "street" },
      ...stamp("2026-09-09T18:30:00Z", "2026-09-09T18:30:00Z", ALEX),
    },
    {
      id: "f_cliff_path", type: "road", revision: 1, name: "Cliff Path",
      geometry: [p(-100, 20), p(-150, -60), p(-195, -135)],
      props: { roadClass: "path" },
      ...stamp("2026-09-12T16:00:00Z"),
    },
    {
      id: "f_red_line", type: "railway", revision: 1, name: "Red Line",
      geometry: [p(-250, -120), p(0, -100), p(250, -120)],
      props: { colour: "#d62828" },
      ...stamp("2026-09-10T20:00:00Z"),
    },
    {
      id: "f_blue_line", type: "railway", revision: 2, name: "Blue Line",
      geometry: [p(50, -250), p(40, -100), p(60, 150), p(160, 250)],
      props: { colour: "#1d4ed8" },
      ...stamp("2026-09-10T21:00:00Z", "2026-09-14T11:00:00Z", STEVE, ALEX),
    },
    {
      id: "f_st_central", type: "station", revision: 1, name: "Central",
      geometry: [p(0, -100)], props: { railwayId: "f_red_line" },
      ...stamp("2026-09-10T20:10:00Z"),
    },
    {
      id: "f_st_westgate", type: "station", revision: 1, name: "Westgate",
      geometry: [p(-250, -120)], props: { railwayId: "f_red_line" },
      ...stamp("2026-09-10T20:11:00Z"),
    },
    {
      id: "f_st_harbour", type: "station", revision: 1, name: "Harbour",
      geometry: [p(250, -120)], props: { railwayId: "f_red_line" },
      ...stamp("2026-09-10T20:12:00Z"),
    },
    {
      id: "f_st_north_park", type: "station", revision: 1, name: "North Park",
      geometry: [p(50, -250)], props: { railwayId: "f_blue_line" },
      ...stamp("2026-09-10T21:10:00Z", "2026-09-10T21:10:00Z", ALEX),
    },
    {
      id: "f_st_market", type: "station", revision: 1, name: "Market Square",
      geometry: [p(40, -100)], props: { railwayId: "f_blue_line" },
      ...stamp("2026-09-10T21:11:00Z", "2026-09-10T21:11:00Z", ALEX),
    },
    {
      id: "f_st_foundry", type: "station", revision: 1, name: "Foundry",
      geometry: [p(60, 150)], props: { railwayId: "f_blue_line" },
      ...stamp("2026-09-10T21:12:00Z", "2026-09-10T21:12:00Z", ALEX),
    },
  ],
  "minecraft:the_nether": [
    {
      id: "f_nether_highway", type: "road", revision: 1, name: "Nether Ice Highway",
      geometry: [p(0, 0), p(0, -400)],
      props: { roadClass: "highway" },
      ...stamp("2026-09-12T22:00:00Z"),
    },
  ],
};

export interface FixtureOptions {
  auth?: AuthMe;
  /** Artificial latency in ms (default 0). */
  delayMs?: number;
}

function deepCopy<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}

export class FixtureApiClient implements ApiClient {
  private readonly worlds: unknown[];
  private readonly features: Record<string, unknown[]>;
  private readonly auth: AuthMe;
  private readonly delayMs: number;

  constructor(options: FixtureOptions = {}) {
    this.worlds = deepCopy(FIXTURE_WORLDS);
    this.features = deepCopy(FIXTURE_FEATURES);
    this.auth = options.auth ?? { loggedIn: false };
    this.delayMs = options.delayMs ?? 0;
  }

  private async respond<T>(signal: AbortSignal | undefined, make: () => T): Promise<T> {
    if (this.delayMs > 0) await new Promise((r) => setTimeout(r, this.delayMs));
    if (signal?.aborted) throw new DOMException("The operation was aborted.", "AbortError");
    return make();
  }

  listWorlds(signal?: AbortSignal): Promise<World[]> {
    return this.respond(signal, () => unwrap(parseWorlds(deepCopy(this.worlds))));
  }

  listFeatures(worldId: string, signal?: AbortSignal): Promise<ParsedFeatureList> {
    return this.respond(signal, () => {
      if (!this.worlds.some((w) => (w as World).id === worldId)) {
        throw new ApiError("http", `Unknown world ${worldId} (HTTP 404)`, 404, "world_not_found");
      }
      return unwrap(parseFeatureList({ schemaVersion: 1, features: deepCopy(this.features[worldId] ?? []) }));
    });
  }

  me(signal?: AbortSignal): Promise<AuthMe> {
    return this.respond(signal, () => unwrap(parseAuthMe(deepCopy(this.auth))));
  }
}

function unwrap<T>(r: { ok: true; value: T } | { ok: false; problem: string }): T {
  if (!r.ok) throw new ApiError("invalid_response", `fixture: ${r.problem}`);
  return r.value;
}
