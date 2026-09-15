/** Duration / distance formatting for the directions panel. Pure. */

/** `m:ss min` under an hour, `h:mm h` from an hour (seconds rounded). */
export function formatClock(seconds: number): string {
  const s = Math.max(0, Math.round(Number.isFinite(seconds) ? seconds : 0));
  if (s < 3600) {
    const m = Math.floor(s / 60);
    return `${m}:${String(s % 60).padStart(2, "0")} min`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return `${h}:${String(m).padStart(2, "0")} h`;
}

/** Blocks, rounded, with comma thousands separators (locale-independent for stable output). */
export function formatBlocks(distance: number): string {
  const n = Math.max(0, Math.round(Number.isFinite(distance) ? distance : 0));
  const txt = String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${txt} ${n === 1 ? "block" : "blocks"}`;
}
