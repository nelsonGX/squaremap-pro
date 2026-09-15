/** Per-type layer visibility. Pure. */
import { FEATURE_TYPES, type Feature, type FeatureType } from "../api/types";

export type LayerVisibility = Record<FeatureType, boolean>;

export const ALL_VISIBLE: LayerVisibility = { building: true, road: true, railway: true, station: true };

export function visibleFeatures(features: readonly Feature[], visibility: LayerVisibility): Feature[] {
  return features.filter((f) => visibility[f.type]);
}

export function countByType(features: readonly Feature[]): Record<FeatureType, number> {
  const counts = Object.fromEntries(FEATURE_TYPES.map((t) => [t, 0])) as Record<FeatureType, number>;
  for (const f of features) counts[f.type]++;
  return counts;
}

/** Formats an ISO timestamp for display; returns the raw string when it does not parse. */
export function formatTimestamp(iso: string, locale?: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(locale, { dateStyle: "medium", timeStyle: "short" });
}
