/**
 * In-memory fixture API for `NEXT_PUBLIC_USE_FIXTURES=1` (dev without a server) and unit tests.
 * Not an App Router route (static export forbids those). Responses are passed through the same
 * guards as HTTP responses, so fixture data cannot drift from the schema unnoticed.
 */
import { validateInput } from "../editor/validate";
import { fixtureRoute } from "./fixtureRoute";
import { ApiError, type ApiClient } from "./client";
import {
  parseAuthMe,
  parseFeature,
  parseFeatureList,
  parsePlayerList,
  parseWorlds,
  type ParsedFeatureList,
} from "./guards";
import type {
  AuthMe,
  Feature,
  FeatureInput,
  FeatureUpdate,
  PlayerList,
  RouteRequest,
  RouteResponse,
  World,
} from "./types";

/** The fixture's logged-in editor. */
export const FIXTURE_PLAYER = { uuid: "0f2c7a4e-5d0b-4c34-9a57-2f1f6c8e9b11", name: "FixtureEditor" };

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
  /** Default: logged in as {@link FIXTURE_PLAYER} with edit permission. */
  auth?: AuthMe;
  /** Artificial latency in ms (default 0). */
  delayMs?: number;
  /** Clock for createdAt/updatedAt (default: now). */
  now?: () => Date;
}

export const FIXTURE_LOGGED_IN: AuthMe = { loggedIn: true, ...FIXTURE_PLAYER, canEdit: true };

function deepCopy<T>(v: T): T {
  return JSON.parse(JSON.stringify(v)) as T;
}

/**
 * In-memory API. Writes mirror the server rules that are cheap to mirror: session + permission
 * (401/403), client validation rules incl. station-on-railway-vertex (400 with details), stale
 * revision (409 with `current`), unknown feature (404), type change (400), deleting a railway that
 * still has stations (422).
 */
/** Fixture players walking slow circles, so the live layer visibly moves in `next dev`. */
export function fixturePlayers(worldId: string, nowMs: number): unknown {
  if (worldId !== "minecraft:overworld") return { players: [], max: 20 };
  const t = nowMs / 1000;
  const walker = (name: string, uuid: string, cx: number, cz: number, radius: number, period: number, phase: number) => {
    const a = ((t / period) * 2 * Math.PI + phase) % (2 * Math.PI);
    return {
      uuid,
      name,
      world: worldId,
      x: Math.round(cx + radius * Math.cos(a)),
      y: 64,
      z: Math.round(cz + radius * Math.sin(a)),
      // Minecraft yaw: 0 = south (+z), increasing clockwise when seen from above.
      yaw: Math.round((((-a * 180) / Math.PI + 90) % 360 + 360) % 360),
    };
  };
  return {
    players: [
      walker(STEVE.name, STEVE.uuid, 0, 0, 70, 40, 0),
      walker(ALEX.name, ALEX.uuid, 150, 90, 45, 55, 2),
      walker(FIXTURE_PLAYER.name, FIXTURE_PLAYER.uuid, -80, 45, 30, 30, 4),
    ],
    max: 20,
  };
}

export class FixtureApiClient implements ApiClient {
  private readonly worlds: World[];
  private readonly features: Map<string, Feature[]>;
  private auth: AuthMe;
  private readonly delayMs: number;
  private readonly now: () => Date;
  private nextId = 1;

  constructor(options: FixtureOptions = {}) {
    this.worlds = unwrap(parseWorlds(deepCopy(FIXTURE_WORLDS)));
    this.features = new Map(
      this.worlds.map((w) => [
        w.id,
        unwrap(parseFeatureList({ schemaVersion: 1, features: deepCopy(FIXTURE_FEATURES[w.id] ?? []) })).features,
      ]),
    );
    this.auth = options.auth ?? FIXTURE_LOGGED_IN;
    this.delayMs = options.delayMs ?? 0;
    this.now = options.now ?? (() => new Date());
  }

  private async respond<T>(signal: AbortSignal | undefined, make: () => T): Promise<T> {
    if (this.delayMs > 0) await new Promise((r) => setTimeout(r, this.delayMs));
    if (signal?.aborted) throw new DOMException("The operation was aborted.", "AbortError");
    return make();
  }

  private list(worldId: string): Feature[] {
    const list = this.features.get(worldId);
    if (!list) throw httpError(404, "world_not_found", `Unknown world ${worldId}`);
    return list;
  }

  private requireEditor(): { uuid: string; name: string } {
    if (!this.auth.loggedIn) throw httpError(401, "unauthorized", "Not logged in");
    if (!this.auth.canEdit) throw httpError(403, "forbidden", "No edit permission");
    return { uuid: this.auth.uuid, name: this.auth.name };
  }

  private validate(list: Feature[], input: FeatureInput): void {
    const details = validateInput(input, (id) => list.find((f) => f.id === id && f.type === "railway")?.geometry);
    if (details.length > 0) throw httpError(400, "validation", "Validation failed", details);
  }

  listWorlds(signal?: AbortSignal): Promise<World[]> {
    return this.respond(signal, () => deepCopy(this.worlds));
  }

  listFeatures(worldId: string, signal?: AbortSignal): Promise<ParsedFeatureList> {
    return this.respond(signal, () => ({ schemaVersion: 1 as const, features: deepCopy(this.list(worldId)), skipped: [] }));
  }

  listPlayers(worldId: string, signal?: AbortSignal): Promise<PlayerList> {
    return this.respond(signal, () => unwrap(parsePlayerList(fixturePlayers(worldId, this.now().getTime()))));
  }

  me(signal?: AbortSignal): Promise<AuthMe> {
    return this.respond(signal, () => unwrap(parseAuthMe(deepCopy(this.auth))));
  }

  route(req: RouteRequest, signal?: AbortSignal): Promise<RouteResponse> {
    return this.respond(signal, () =>
      deepCopy(fixtureRoute(req, this.features.has(req.world), this.features.get(req.world) ?? [])),
    );
  }

  logout(): Promise<void> {
    return this.respond(undefined, () => {
      this.auth = { loggedIn: false };
    });
  }

  createFeature(worldId: string, input: FeatureInput): Promise<Feature> {
    return this.respond(undefined, () => {
      const who = this.requireEditor();
      const list = this.list(worldId);
      const clean = normaliseInput(input);
      this.validate(list, clean);
      const at = this.now().toISOString();
      const feature = unwrap(
        parseFeature({ ...clean, id: `f_fixture_${this.nextId++}`, revision: 1, createdBy: who, createdAt: at, updatedBy: who, updatedAt: at }),
      );
      list.push(feature);
      return deepCopy(feature);
    });
  }

  updateFeature(worldId: string, id: string, update: FeatureUpdate): Promise<Feature> {
    return this.respond(undefined, () => {
      const who = this.requireEditor();
      const list = this.list(worldId);
      const index = list.findIndex((f) => f.id === id);
      const existing = list[index];
      if (!existing) throw httpError(404, "not_found", `Feature ${id} not found`);
      if (update.revision !== existing.revision) {
        throw httpError(409, "conflict", "Stale revision", [], deepCopy(existing));
      }
      if (update.type !== existing.type) {
        throw httpError(400, "validation", "Validation failed", [{ field: "type", message: "type cannot change" }]);
      }
      const { revision: _revision, ...rest } = update;
      void _revision;
      const clean = normaliseInput(rest);
      this.validate(list, clean);
      const feature = unwrap(
        parseFeature({
          ...clean,
          id,
          revision: existing.revision + 1,
          createdBy: existing.createdBy,
          createdAt: existing.createdAt,
          updatedBy: who,
          updatedAt: this.now().toISOString(),
        }),
      );
      list[index] = feature;
      return deepCopy(feature);
    });
  }

  deleteFeature(worldId: string, id: string, revision: number): Promise<void> {
    return this.respond(undefined, () => {
      this.requireEditor();
      const list = this.list(worldId);
      const index = list.findIndex((f) => f.id === id);
      const existing = list[index];
      if (!existing) throw httpError(404, "not_found", `Feature ${id} not found`);
      if (revision !== existing.revision) throw httpError(409, "conflict", "Stale revision", [], deepCopy(existing));
      if (existing.type === "railway" && list.some((f) => f.type === "station" && f.props.railwayId === id)) {
        throw httpError(422, "railway_has_stations", "Delete the railway's stations first");
      }
      list.splice(index, 1);
    });
  }
}

function normaliseInput(input: FeatureInput): FeatureInput {
  return { ...deepCopy(input), name: input.name.trim() } as FeatureInput;
}

function httpError(
  status: number,
  code: string,
  text: string,
  details: ApiError["details"] = [],
  current: Feature | null = null,
): ApiError {
  return new ApiError("http", `${text} (HTTP ${status}: ${code})`, status, code, details, current);
}

function unwrap<T>(r: { ok: true; value: T } | { ok: false; problem: string }): T {
  if (!r.ok) throw new ApiError("invalid_response", `fixture: ${r.problem}`);
  return r.value;
}
