"use client";

/** Directions mode: from/to inputs (coordinates, feature search, map pick), modes, route summary and legs. */
import { useState } from "react";
import type { Feature, RouteMode, RouteResponse, XZ } from "../lib/api/types";
import { searchFeatures } from "../lib/features/search";
import { displayName, featureSubtitle } from "../lib/features/styles";
import { parseCoordInput } from "../lib/navigation/coords";
import { formatBlocks, formatClock } from "../lib/navigation/format";
import { legLineStyle, legView, routeStatusMessage, routeSummary, type NavModes } from "../lib/navigation/legs";
import styles from "./ViewerApp.module.css";

export type Which = "from" | "to";

export interface Endpoint {
  point: XZ;
  /** Feature name when picked from search; null for typed / clicked coordinates. */
  label: string | null;
}

export type RouteView =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "result"; route: RouteResponse };

export interface DirectionsPanelProps {
  texts: Record<Which, string>;
  endpoints: Record<Which, Endpoint | null>;
  pickTarget: Which | null;
  modes: NavModes;
  view: RouteView;
  allFeatures: readonly Feature[];
  railwayNames: ReadonlyMap<string, string>;
  highlightLeg: number | null;
  onText: (which: Which, text: string) => void;
  onPickFeature: (which: Which, feature: Feature) => void;
  onPickTarget: (which: Which | null) => void;
  onSwap: () => void;
  onModes: (modes: NavModes) => void;
  onHoverLeg: (index: number | null) => void;
  onFocusLeg: (index: number) => void;
  onRetry: () => void;
}

function ModeIcon({ mode, colour }: { mode: RouteMode; colour: string }) {
  const common = { width: 18, height: 18, viewBox: "0 0 24 24", "aria-hidden": true } as const;
  switch (mode) {
    case "walk":
      return (
        <svg {...common} fill={colour}>
          <circle cx="13" cy="4" r="2" />
          <path d="M10.5 8.5 7 10.5V15h2v-3.3l1.3-.8L9 21h2.2l1.3-6 2 2.1V21h2v-5.4l-2.3-2.4.6-3a6 6 0 0 0 4.2 2.3v-2a4 4 0 0 1-3.2-2l-1-1.6a2 2 0 0 0-2.5-.8z" />
        </svg>
      );
    case "road":
      return (
        <svg {...common} fill="none" stroke={colour} strokeWidth="2.2" strokeLinecap="round">
          <path d="M8 3 5 21M16 3l3 18M12 4v3M12 11v3M12 18v2" />
        </svg>
      );
    case "rail":
      return (
        <svg {...common} fill={colour}>
          <path d="M7 3h10a3 3 0 0 1 3 3v9a3 3 0 0 1-2.2 2.9L19.5 20h-2.3l-1.5-2H8.3l-1.5 2H4.5l1.7-2.1A3 3 0 0 1 4 15V6a3 3 0 0 1 3-3zm0 2a1 1 0 0 0-1 1v4h12V6a1 1 0 0 0-1-1H7zm1 8a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm8 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z" />
        </svg>
      );
  }
}

export default function DirectionsPanel(props: DirectionsPanelProps) {
  const { texts, endpoints, pickTarget, modes, view, allFeatures, railwayNames, highlightLeg } = props;
  const [focused, setFocused] = useState<Which | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set());
  const [expandedFor, setExpandedFor] = useState<RouteResponse | null>(null);

  // Collapse step lists when a new route arrives (state derived during render, no effect).
  const currentRoute = view.kind === "result" ? view.route : null;
  if (currentRoute !== expandedFor) {
    setExpandedFor(currentRoute);
    setExpanded(new Set());
  }

  const suggestions = (which: Which): Feature[] => {
    const t = texts[which];
    if (focused !== which || t.trim() === "" || parseCoordInput(t) || endpoints[which]?.label === t) return [];
    return searchFeatures(allFeatures, t, 6);
  };

  const field = (which: Which) => {
    const label = which === "from" ? "Start" : "Destination";
    const results = suggestions(which);
    const invalid = texts[which].trim() !== "" && !endpoints[which] && results.length === 0 && focused !== which;
    const id = `nav-${which}`;
    return (
      <div className={styles.navField}>
        <span className={`${styles.navDot} ${which === "from" ? styles.navDotFrom : styles.navDotTo}`} aria-hidden="true">
          {which === "from" ? "A" : "B"}
        </span>
        <div className={styles.navInputWrap}>
          <input
            id={id}
            className={styles.navInput}
            placeholder={`${label}: "x, z", a place name, or pick on map`}
            aria-label={label}
            value={texts[which]}
            onChange={(e) => props.onText(which, e.target.value)}
            onFocus={() => setFocused(which)}
            onBlur={() => setFocused((f) => (f === which ? null : f))}
            onKeyDown={(e) => {
              if (e.key === "Enter" && results[0]) {
                props.onPickFeature(which, results[0]);
                (e.target as HTMLInputElement).blur();
              }
            }}
            aria-invalid={invalid}
            autoComplete="off"
            spellCheck={false}
          />
          {results.length > 0 && (
            <ul className={styles.suggestions} role="listbox" aria-label={`${label} suggestions`}>
              {results.map((f) => (
                <li key={f.id} role="option" aria-selected={false}>
                  <button
                    type="button"
                    className={styles.result}
                    // onMouseDown so the pick happens before the input's blur hides the list.
                    onMouseDown={(e) => {
                      e.preventDefault();
                      props.onPickFeature(which, f);
                      setFocused(null);
                    }}
                  >
                    <span className={styles.resultText}>
                      <span className={styles.resultName}>{displayName(f)}</span>
                      <span className={styles.resultSub}>{featureSubtitle(f, railwayNames)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {invalid && <p className={styles.fieldErrors}>Enter whole block coordinates like “12, -40”, or pick a place.</p>}
        </div>
        <button
          type="button"
          className={styles.iconButton}
          aria-pressed={pickTarget === which}
          title={`Pick ${label.toLowerCase()} on the map`}
          aria-label={`Pick ${label.toLowerCase()} on the map`}
          onClick={() => props.onPickTarget(pickTarget === which ? null : which)}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <circle cx="12" cy="12" r="7" />
            <path d="M12 2v4M12 18v4M2 12h4M18 12h4" />
          </svg>
        </button>
      </div>
    );
  };

  const route = currentRoute;
  const statusMessage = route ? routeStatusMessage(route) : null;

  return (
    <section className={styles.directions} aria-label="Directions">
      <div className={styles.navFields}>
        <div className={styles.navFieldStack}>
          {field("from")}
          {field("to")}
        </div>
        <button type="button" className={styles.iconButton} onClick={props.onSwap} aria-label="Swap start and destination" title="Swap">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M7 4v16M7 4 3 8M7 4l4 4M17 20V4M17 20l-4-4M17 20l4-4" />
          </svg>
        </button>
      </div>

      {pickTarget && (
        <p className={styles.hint} role="status">
          Click the map to set the {pickTarget === "from" ? "start" : "destination"}.
        </p>
      )}

      <div className={styles.chips} role="group" aria-label="Travel modes">
        <button type="button" className={styles.chip} aria-pressed={modes.road} onClick={() => props.onModes({ ...modes, road: !modes.road })}>
          <ModeIcon mode="road" colour="#1a73e8" /> Roads
        </button>
        <button type="button" className={styles.chip} aria-pressed={modes.rail} onClick={() => props.onModes({ ...modes, rail: !modes.rail })}>
          <ModeIcon mode="rail" colour="#7b1fa2" /> Railways
        </button>
        <span className={styles.muted}>Walking always included</span>
      </div>

      <div aria-live="polite">
        {view.kind === "idle" && <p className={styles.hint}>Choose a start and a destination.</p>}
        {view.kind === "loading" && <p className={styles.muted}>Finding a route…</p>}
        {view.kind === "error" && (
          <div className={styles.error} role="alert">
            <p>{view.message}</p>
            <button type="button" className={styles.textButton} onClick={props.onRetry}>
              Retry
            </button>
          </div>
        )}
        {statusMessage && (
          <p className={styles.warn} role="alert">
            {statusMessage}
          </p>
        )}
      </div>

      {route && route.status === "ok" && (
        <>
          <div className={styles.routeSummary}>
            <span className={styles.routeDuration}>{formatClock(route.duration)}</span>
            <span className={styles.routeDistance}>{formatBlocks(route.distance)}</span>
            <span className={styles.routeVia}>{routeSummary(route)}</span>
          </div>
          <ol className={styles.legs}>
            {route.legs.map((leg, i) => {
              const v = legView(leg, i, route.legs);
              const colour = legLineStyle(leg, allFeatures).color;
              const open = expanded.has(i);
              return (
                <li
                  key={i}
                  className={`${styles.leg} ${highlightLeg === i ? styles.legHighlighted : ""}`}
                  onMouseEnter={() => props.onHoverLeg(i)}
                  onMouseLeave={() => props.onHoverLeg(null)}
                >
                  <span className={styles.legIcon} style={{ borderColor: colour }}>
                    <ModeIcon mode={leg.mode} colour={colour} />
                  </span>
                  <div className={styles.legBody}>
                    <button type="button" className={styles.legTitle} onClick={() => props.onFocusLeg(i)} title="Show on map">
                      {v.title}
                    </button>
                    {v.detail && <p className={styles.legDetail}>{v.detail}</p>}
                    <p className={styles.legMeta}>{v.meta}</p>
                    {v.steps.length > 0 && (
                      <>
                        <button
                          type="button"
                          className={styles.linkButton}
                          aria-expanded={open}
                          onClick={() =>
                            setExpanded((s) => {
                              const n = new Set(s);
                              if (n.has(i)) n.delete(i);
                              else n.add(i);
                              return n;
                            })
                          }
                        >
                          {open ? "Hide steps" : `${v.steps.length} step${v.steps.length === 1 ? "" : "s"}`}
                        </button>
                        {open && (
                          <ol className={styles.steps}>
                            {v.steps.map((s, k) => (
                              <li key={k}>
                                <span>{s.text}</span>
                                {s.kind !== "arrive" && <span className={styles.muted}>{formatBlocks(s.distance)}</span>}
                              </li>
                            ))}
                          </ol>
                        )}
                      </>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
        </>
      )}
    </section>
  );
}
