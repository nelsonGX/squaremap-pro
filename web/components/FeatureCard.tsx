"use client";

import type { ReactNode } from "react";
import type { Feature } from "../lib/api/types";
import { polylineLength, ringArea } from "../lib/features/geometry";
import { interchangePeers } from "../lib/features/interchange";
import { formatTimestamp } from "../lib/features/layers";
import {
  CATEGORY_COLOURS,
  CATEGORY_LABELS,
  displayName,
  ROAD_CLASS_LABELS,
  ROAD_LOOKS,
  TYPE_LABELS,
  UNKNOWN_RAILWAY_COLOUR,
} from "../lib/features/styles";
import { BackIcon, CloseIcon, DirectionsIcon } from "./icons";
import styles from "./ViewerApp.module.css";

export interface FeatureCardProps {
  feature: Feature;
  /** All features of the world, for railway ↔ station links. */
  allFeatures: readonly Feature[];
  onClose: () => void;
  onOpen: (featureId: string) => void;
  /** Shown when the card was opened from search results. */
  onBack?: () => void;
  /** "Directions to here". */
  onDirections?: (feature: Feature) => void;
}

const fmtBlocks = (n: number) => `${Math.round(n).toLocaleString()} blocks`;

function Swatch({ colour }: { colour: string }) {
  return <span className={styles.swatch} style={{ background: colour }} aria-hidden="true" />;
}

export default function FeatureCard({ feature: f, allFeatures, onClose, onOpen, onBack, onDirections }: FeatureCardProps) {
  let accent: string;
  let classRow: { label: string; value: ReactNode } | null = null;
  const facts: { label: string; value: ReactNode }[] = [];
  let links: { title: string; items: Feature[] } | null = null;

  switch (f.type) {
    case "building":
      accent = CATEGORY_COLOURS[f.props.category];
      classRow = { label: "Category", value: CATEGORY_LABELS[f.props.category] };
      facts.push({ label: "Footprint", value: `${Math.round(ringArea(f.geometry)).toLocaleString()} blocks²` });
      break;
    case "road":
      accent = ROAD_LOOKS[f.props.roadClass].color;
      classRow = { label: "Class", value: ROAD_CLASS_LABELS[f.props.roadClass] };
      facts.push({ label: "Length", value: fmtBlocks(polylineLength(f.geometry)) });
      break;
    case "railway": {
      accent = f.props.colour;
      classRow = {
        label: "Colour",
        value: (
          <>
            <Swatch colour={f.props.colour} /> {f.props.colour}
          </>
        ),
      };
      facts.push({ label: "Length", value: fmtBlocks(polylineLength(f.geometry)) });
      const id = f.id;
      links = {
        title: "Stations",
        items: allFeatures.filter((s) => s.type === "station" && s.props.railwayId === id),
      };
      break;
    }
    case "station": {
      const railwayId = f.props.railwayId;
      const railway = allFeatures.find((r) => r.id === railwayId && r.type === "railway");
      accent = railway?.type === "railway" ? railway.props.colour : UNKNOWN_RAILWAY_COLOUR;
      const p = f.geometry[0]!;
      facts.push({ label: "Location", value: `${p.x}, ${p.z}` });
      /*
       * A station belongs to one railway, so a crossing served by several lines is several station
       * features. Nearby ones are drawn as a single interchange, and the card follows: it lists
       * every line served here, not just this feature's own.
       */
      const peers = interchangePeers(allFeatures, f.id);
      const lines: Feature[] = [];
      for (const id of [railwayId, ...peers.map((s) => s.props.railwayId)]) {
        if (lines.some((r) => r.id === id)) continue;
        const r = allFeatures.find((x) => x.id === id && x.type === "railway");
        if (r) lines.push(r);
      }
      if (peers.length > 0) {
        facts.push({ label: "Interchange", value: `${peers.length + 1} stations · ${lines.length} lines` });
      }
      links = { title: lines.length > 1 ? "Lines" : "Line", items: lines };
      break;
    }
  }

  return (
    <article className={styles.card} aria-labelledby="feature-card-title">
      <div className={styles.cardAccent} style={{ background: accent }} />
      <div className={styles.cardHeader}>
        {onBack && (
          <button type="button" className={styles.iconButton} onClick={onBack} aria-label="Back to results" title="Back to results">
            <BackIcon size={20} />
          </button>
        )}
        <div className={styles.cardTitles}>
          <h2 id="feature-card-title" className={styles.cardTitle}>
            {displayName(f)}
          </h2>
          <p className={styles.cardType}>{TYPE_LABELS[f.type]}</p>
        </div>
        <button type="button" className={styles.iconButton} onClick={onClose} aria-label="Close" title="Close">
          <CloseIcon size={20} />
        </button>
      </div>

      {onDirections && (
        <div className={styles.cardActions}>
          <button type="button" className={styles.actionButton} onClick={() => onDirections(f)}>
            <span className={styles.actionIcon} aria-hidden="true">
              <DirectionsIcon size={20} />
            </span>
            Directions
          </button>
        </div>
      )}

      {f.type === "building" && f.props.description.trim() !== "" && (
        <p className={styles.cardDescription}>{f.props.description}</p>
      )}

      <dl className={styles.facts}>
        {classRow && (
          <div>
            <dt>{classRow.label}</dt>
            <dd>{classRow.value}</dd>
          </div>
        )}
        {facts.map((x) => (
          <div key={x.label}>
            <dt>{x.label}</dt>
            <dd>{x.value}</dd>
          </div>
        ))}
        <div>
          <dt>Updated</dt>
          <dd>
            {f.updatedBy.name} · <time dateTime={f.updatedAt}>{formatTimestamp(f.updatedAt)}</time>
          </dd>
        </div>
        {(f.createdBy.uuid !== f.updatedBy.uuid || f.createdAt !== f.updatedAt) && (
          <div>
            <dt>Created</dt>
            <dd>
              {f.createdBy.name} · <time dateTime={f.createdAt}>{formatTimestamp(f.createdAt)}</time>
            </dd>
          </div>
        )}
      </dl>

      {links && (
        <section className={styles.links}>
          <h3>{links.title}</h3>
          {links.items.length === 0 ? (
            <p className={styles.muted}>{f.type === "station" ? "Railway not found" : "No stations yet"}</p>
          ) : (
            <ul>
              {links.items.map((x) => (
                <li key={x.id}>
                  <button type="button" className={styles.linkButton} onClick={() => onOpen(x.id)}>
                    {displayName(x)}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </article>
  );
}
