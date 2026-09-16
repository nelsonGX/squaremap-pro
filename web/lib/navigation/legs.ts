/** Route leg presentation (titles, steps, map styles) and route request helpers. Pure. */
import type { Feature, RouteLeg, RouteMode, RouteRequest, RouteResponse, RouteStatus, XZ } from "../api/types";
import { buildDirections, type Step } from "../directions";
import { coordParam } from "./coords";
import { formatBlocks, formatClock } from "./format";

export type NavModes = { road: boolean; rail: boolean };

export const ALL_NAV_MODES: NavModes = { road: true, rail: true };

/**
 * `modes` query value. WALK is always used by the server for access/egress/transfers, so only road
 * and rail are gated; walk is sent too for readability. Undefined when everything is enabled
 * (server default).
 */
export function modesParam(modes: NavModes): RouteMode[] | undefined {
  if (modes.road && modes.rail) return undefined;
  const out: RouteMode[] = ["walk"];
  if (modes.road) out.push("road");
  if (modes.rail) out.push("rail");
  return out;
}

/** `/api/route?world=&from=x,z&to=x,z[&modes=]` with ":" and "," kept readable. */
export function routePath(req: RouteRequest): string {
  const params = new URLSearchParams({ world: req.world, from: coordParam(req.from), to: coordParam(req.to) });
  if (req.modes) params.set("modes", req.modes.join(","));
  return `/api/route?${params.toString().replace(/%2C/gi, ",").replace(/%3A/gi, ":")}`;
}

/** HTTP status the server uses for each route status (CLAUDE.md route schema v2). */
export function httpStatusForRoute(status: RouteStatus): number {
  switch (status) {
    case "ok":
    case "no_path":
      return 200;
    case "invalid_request":
      return 400;
    case "world_not_found":
      return 404;
  }
}

export const MODE_LABELS: Record<RouteMode, string> = { walk: "Walk", road: "Road", rail: "Train" };

/** Apple's system blue, used for every driving leg. */
export const ROAD_LEG_COLOUR = "#0a84ff";

/** Apple's system indigo, for a rail leg whose railway is not in the feature list. */
export const RAIL_FALLBACK_COLOUR = "#5e5ce6";

export interface LegView {
  mode: RouteMode;
  title: string;
  /** e.g. "Board at Central → alight at Harbour" for rail. */
  detail: string | null;
  meta: string;
  /** Turn-by-turn for walk/road legs (empty for rail and zero-length legs). */
  steps: Step[];
}

/** Railway colour for a rail leg, by railway name (first railway with that name). */
export function railLegColour(leg: RouteLeg, features: readonly Feature[]): string {
  const r = features.find((f) => f.type === "railway" && f.name === leg.name);
  return r?.type === "railway" ? r.props.colour : RAIL_FALLBACK_COLOUR;
}

/**
 * Steps for one leg. Walk/road legs get `buildDirections`; the trailing "arrive" step is kept only on
 * the last leg. Zero-length legs have no steps.
 */
export function legSteps(leg: RouteLeg, isLast: boolean): Step[] {
  if (leg.mode === "rail" || leg.distance === 0) return [];
  const steps = buildDirections(leg.points);
  return isLast ? steps : steps.filter((s) => s.kind !== "arrive");
}

export function legView(leg: RouteLeg, index: number, legs: readonly RouteLeg[]): LegView {
  const isLast = index === legs.length - 1;
  const meta = `${formatClock(leg.duration)} · ${formatBlocks(leg.distance)}`;
  switch (leg.mode) {
    case "walk": {
      const next = legs[index + 1];
      const target = isLast
        ? "destination"
        : next?.mode === "rail" && next.fromStation
          ? `${next.fromStation} station`
          : next?.name ?? "the next leg";
      return {
        mode: "walk",
        title: leg.distance === 0 ? "You are already there" : `Walk to ${target}`,
        detail: null,
        meta,
        steps: legSteps(leg, isLast),
      };
    }
    case "road":
      return {
        mode: "road",
        title: leg.name ? `Follow ${leg.name}` : "Follow the road",
        detail: null,
        meta,
        steps: legSteps(leg, isLast),
      };
    case "rail":
      return {
        mode: "rail",
        title: leg.name ? `Take ${leg.name}` : "Take the train",
        detail: `Board at ${leg.fromStation ?? "?"} → alight at ${leg.toStation ?? "?"}`,
        meta,
        steps: [],
      };
  }
}

/** One-line summary of the modes used, e.g. "Walk · Main Street · Red Line". */
export function routeSummary(route: RouteResponse): string {
  const parts: string[] = [];
  for (const leg of route.legs) {
    const label = leg.mode === "walk" ? MODE_LABELS.walk : (leg.name ?? MODE_LABELS[leg.mode]);
    if (parts[parts.length - 1] !== label) parts.push(label);
  }
  return parts.join(" · ");
}

/** User-facing text for non-ok statuses. */
export function routeStatusMessage(route: RouteResponse): string | null {
  switch (route.status) {
    case "ok":
      return null;
    case "no_path":
      return "No route found between these points.";
    case "invalid_request":
      return `The route request was rejected${route.error ? `: ${route.error}` : "."}`;
    case "world_not_found":
      return "Navigation is not available for this world.";
  }
}

export interface LegLineStyle {
  color: string;
  weight: number;
  opacity: number;
  dashArray?: string;
  casing: { color: string; weight: number } | null;
}

/**
 * Map style per leg, following Apple Maps' route drawing: walking legs are a row of grey dots,
 * driving legs the system blue inside a darker blue casing, transit legs the line's own colour
 * inside the same darker casing so every leg reads as one continuous ribbon.
 */
export function legLineStyle(leg: RouteLeg, features: readonly Feature[]): LegLineStyle {
  switch (leg.mode) {
    case "walk":
      return { color: "#8e8e93", weight: 5, opacity: 0.95, dashArray: "0.1 9", casing: null };
    case "road":
      return { color: ROAD_LEG_COLOUR, weight: 8, opacity: 1, casing: { color: "#0059c8", weight: 12 } };
    case "rail":
      return { color: railLegColour(leg, features), weight: 8, opacity: 1, casing: { color: "#ffffff", weight: 13 } };
  }
}

/** All points of a route (for bounds), including from/to. */
export function routePoints(route: RouteResponse): XZ[] {
  const pts: XZ[] = [];
  if (route.from) pts.push(route.from);
  for (const leg of route.legs) pts.push(...leg.points);
  if (route.to) pts.push(route.to);
  return pts;
}
