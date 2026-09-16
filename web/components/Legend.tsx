"use client";

/**
 * Map legend, bottom left above the world picker.
 *
 * The map draws a road hierarchy in four widths, buildings in twelve tints and every railway in a
 * colour its editor chose — none of which means anything to a first-time visitor. The legend is the
 * key to that, and the railway section doubles as the line index: clicking a line selects it.
 *
 * Collapsed by default so it never competes with the map; the open state is local because nothing
 * else in the app depends on it.
 */
import { useState } from "react";
import type { Feature, RailwayFeature } from "../lib/api/types";
import { BUILDING_CATEGORIES, ROAD_CLASSES } from "../lib/api/types";
import {
  CATEGORY_COLOURS,
  CATEGORY_LABELS,
  CATEGORY_STROKES,
  displayName,
  ROAD_CLASS_LABELS,
  ROAD_LOOKS,
} from "../lib/features/styles";
import { ChevronIcon, LegendIcon } from "./icons";
import styles from "./Legend.module.css";

export interface LegendProps {
  /** All features of the current world; the railway rows come from these. */
  features: readonly Feature[];
  /** Selecting a railway row opens it, as a search result would. */
  onSelectRailway: (f: RailwayFeature) => void;
}

function railways(features: readonly Feature[]): RailwayFeature[] {
  return features
    .filter((f): f is RailwayFeature => f.type === "railway")
    .sort((a, b) => displayName(a).localeCompare(displayName(b)));
}

export default function Legend({ features, onSelectRailway }: LegendProps) {
  const [open, setOpen] = useState(false);
  const lines = railways(features);

  return (
    <div className={styles.legend}>
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls="map-legend"
        onClick={() => setOpen((o) => !o)}
        title="What the colours on the map mean"
      >
        <span className={styles.toggleIcon} aria-hidden="true">
          <LegendIcon size={20} />
        </span>
        Legend
        <span className={`${styles.chevron} ${open ? styles.chevronOpen : ""}`} aria-hidden="true">
          <ChevronIcon size={16} />
        </span>
      </button>

      {open && (
        <div id="map-legend" className={styles.card}>
          <h2 className={styles.heading}>Roads</h2>
          <ul className={styles.list}>
            {ROAD_CLASSES.map((c) => (
              <li key={c} className={styles.row}>
                <span
                  className={styles.roadSwatch}
                  style={{
                    // Same fill-inside-casing construction the map draws, at legend scale.
                    background: ROAD_LOOKS[c].color,
                    outlineColor: ROAD_LOOKS[c].casing,
                    height: `${Math.max(3, ROAD_LOOKS[c].weight / 1.6)}px`,
                    borderStyle: c === "path" ? "dotted" : "none",
                  }}
                  aria-hidden="true"
                />
                <span className={styles.rowLabel}>{ROAD_CLASS_LABELS[c]}</span>
              </li>
            ))}
          </ul>

          {lines.length > 0 && (
            <>
              <h2 className={styles.heading}>Railways</h2>
              <ul className={styles.list}>
                {lines.map((f) => (
                  <li key={f.id}>
                    <button type="button" className={styles.lineRow} onClick={() => onSelectRailway(f)}>
                      <span className={styles.linePill} style={{ background: f.props.colour }} aria-hidden="true" />
                      <span className={styles.rowLabel}>{displayName(f)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}

          <h2 className={styles.heading}>Buildings</h2>
          <ul className={`${styles.list} ${styles.grid}`}>
            {BUILDING_CATEGORIES.map((c) => (
              <li key={c} className={styles.row}>
                <span
                  className={styles.buildingSwatch}
                  style={{ background: CATEGORY_COLOURS[c], borderColor: CATEGORY_STROKES[c] }}
                  aria-hidden="true"
                />
                <span className={styles.rowLabel}>{CATEGORY_LABELS[c]}</span>
              </li>
            ))}
          </ul>

          <p className={styles.note}>
            Footprints, station dots and street names appear as you zoom in.
          </p>
        </div>
      )}
    </div>
  );
}
