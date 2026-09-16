/**
 * Types for the squaremap-pro HTTP API (CLAUDE.md "HTTP API": Auth, Features v1, Route schema v2).
 * All coordinates are integer world block x/z; clients draw at block centre (x + 0.5, z + 0.5).
 */

export interface XZ {
  x: number;
  z: number;
}

/** `GET /api/worlds` element. `id` is squaremap `WorldIdentifier.asString()`, e.g. `minecraft:overworld`. */
export interface World {
  id: string;
  name: string;
}

export type AuthMe = { loggedIn: false } | { loggedIn: true; uuid: string; name: string; canEdit: boolean };

export const FEATURE_TYPES = ["building", "road", "railway", "station"] as const;
export type FeatureType = (typeof FEATURE_TYPES)[number];

export const BUILDING_CATEGORIES = ["residential", "commercial", "public", "industrial", "other"] as const;
export type BuildingCategory = (typeof BUILDING_CATEGORIES)[number];

export const ROAD_CLASSES = ["highway", "main", "street", "path"] as const;
export type RoadClass = (typeof ROAD_CLASSES)[number];

export interface PlayerRef {
  uuid: string;
  name: string;
}

/** `GET /api/players` element: one online player, as the server last saw them. */
export interface OnlinePlayer {
  /** Dashed UUID. */
  uuid: string;
  name: string;
  /** World id the player is in (`minecraft:overworld`). */
  world: string;
  x: number;
  y: number;
  z: number;
  /** Head rotation, degrees clockwise from south (Minecraft yaw), normalised to 0..359. */
  yaw: number;
}

/** `GET /api/players[?world=]` */
export interface PlayerList {
  players: OnlinePlayer[];
  /** The server's player slot count. */
  max: number;
}

export interface BuildingProps {
  category: BuildingCategory;
  description: string;
}
export interface RoadProps {
  roadClass: RoadClass;
}
export interface RailwayProps {
  /** `#rrggbb` */
  colour: string;
}
export interface StationProps {
  railwayId: string;
}

export interface FeaturePropsByType {
  building: BuildingProps;
  road: RoadProps;
  railway: RailwayProps;
  station: StationProps;
}

interface FeatureOf<T extends FeatureType> {
  id: string;
  type: T;
  revision: number;
  name: string;
  /**
   * building: closed ring, >= 3 vertices, first != last. road/railway: polyline, >= 2 vertices.
   * station: exactly 1 vertex.
   */
  geometry: XZ[];
  props: FeaturePropsByType[T];
  createdBy: PlayerRef;
  createdAt: string;
  updatedBy: PlayerRef;
  updatedAt: string;
}

export type BuildingFeature = FeatureOf<"building">;
export type RoadFeature = FeatureOf<"road">;
export type RailwayFeature = FeatureOf<"railway">;
export type StationFeature = FeatureOf<"station">;
export type Feature = BuildingFeature | RoadFeature | RailwayFeature | StationFeature;

/** `GET /api/worlds/{world}/features` */
export interface FeatureList {
  schemaVersion: 1;
  features: Feature[];
}

type InputOf<T extends FeatureType> = {
  type: T;
  name: string;
  geometry: XZ[];
  props: FeaturePropsByType[T];
};

/** `POST /api/worlds/{world}/features` body. */
export type FeatureInput =
  | InputOf<"building">
  | InputOf<"road">
  | InputOf<"railway">
  | InputOf<"station">;

/** `PUT /api/worlds/{world}/features/{id}` body. */
export type FeatureUpdate = FeatureInput & { revision: number };

export interface ValidationDetail {
  field: string;
  message: string;
}

/** Error body for 400/401/403/404/409/422. */
export interface ApiErrorBody {
  error: string;
  details: ValidationDetail[];
}

// ---- Route schema v2 ---------------------------------------------------------------------------

export const ROUTE_MODES = ["walk", "road", "rail"] as const;
export type RouteMode = (typeof ROUTE_MODES)[number];

export const ROUTE_STATUSES = ["ok", "no_path", "invalid_request", "world_not_found"] as const;
export type RouteStatus = (typeof ROUTE_STATUSES)[number];

export interface RouteLeg {
  mode: RouteMode;
  name: string | null;
  points: XZ[];
  /** Present on rail legs. */
  fromStation?: string;
  toStation?: string;
  distance: number;
  duration: number;
}

export interface RouteResponse {
  schemaVersion: 2;
  status: RouteStatus;
  world: string;
  from: XZ | null;
  to: XZ | null;
  legs: RouteLeg[];
  distance: number;
  duration: number;
  error: string | null;
}

export interface RouteRequest {
  world: string;
  from: XZ;
  to: XZ;
  /** Omitted = server default (all modes). */
  modes?: RouteMode[];
}
