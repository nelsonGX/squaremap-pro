/** Feature search by name. Pure. */
import type { Feature } from "../api/types";

/** Case-insensitive, locale-independent normalisation for matching. */
function norm(s: string): string {
  return s.trim().toLowerCase();
}

/**
 * Features whose name contains `query` (case-insensitive substring, query trimmed). Empty query → [].
 * Ordering: name starts with the query first, then word-start matches, then other matches; ties by
 * name (then id) for stable output. At most `limit` results.
 */
export function searchFeatures(features: readonly Feature[], query: string, limit = 50): Feature[] {
  const q = norm(query);
  if (q === "") return [];
  const scored: { f: Feature; score: number; name: string }[] = [];
  for (const f of features) {
    const name = norm(f.name);
    const idx = name.indexOf(q);
    if (idx < 0) continue;
    const score = idx === 0 ? 0 : /[^\p{L}\p{N}]/u.test(name[idx - 1]!) ? 1 : 2;
    scored.push({ f, score, name });
  }
  scored.sort((a, b) => a.score - b.score || a.name.localeCompare(b.name) || a.f.id.localeCompare(b.f.id));
  return scored.slice(0, Math.max(0, limit)).map((s) => s.f);
}
