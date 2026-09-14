"use client";

import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ROUTE_API, SQUAREMAP_URL, USING_MOCK_ROUTE_API, USING_MOCK_SQUAREMAP } from "../lib/config";
import {
  buildDirections,
  formatDuration,
  walkingSeconds,
  type Step,
} from "../lib/directions";
import {
  buildRouteUrl,
  fetchRoute,
  formatCoord,
  parseCoordInput,
  type RouteResult,
} from "../lib/routeClient";
import { tileUrlTemplate } from "../lib/squaremapCrs";
import {
  isSettings,
  isWorldSettings,
  sortWorlds,
  webNameToWorldId,
  type SettingsWorld,
  type WorldSettings,
} from "../lib/squaremapSettings";
import styles from "./NavApp.module.css";

const MapView = dynamic(() => import("./MapView"), {
  ssr: false,
  loading: () => <div className={styles.mapLoading}>Loading map…</div>,
});

/** Used until a world's settings.json has loaded (squaremap WorldConfig defaults: 3 / 3 / 2). */
const FALLBACK_WORLD_SETTINGS: WorldSettings = {
  spawn: { x: 0, z: 0 },
  zoom: { max: 3, def: 3, extra: 2 },
  marker_update_interval: 5,
  tiles_update_interval: 15,
};

type RequestState = { kind: "idle" } | { kind: "loading" } | { kind: "input_error"; message: string } | RouteResult;

type Target = "from" | "to";

async function getJson(url: string, signal: AbortSignal): Promise<unknown> {
  const res = await fetch(url, { cache: "no-store", signal });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.json();
}

function failureMessage(state: RequestState): { title: string; detail?: string } | null {
  switch (state.kind) {
    case "input_error":
      return { title: state.message };
    case "no_path":
      return {
        title: "No walkable route was found between these points.",
        detail: "The destination may be enclosed, across water or void, or unreachable on foot.",
      };
    case "cap_exceeded":
      return {
        title: "The route is too long or complex to compute.",
        detail: "The search limit was reached before a path was found. Try points closer together.",
      };
    case "invalid_request":
      return {
        title: "The route service rejected the request.",
        detail: state.payload.error ?? undefined,
      };
    case "world_not_found":
      return {
        title: "This world is not available for navigation.",
        detail: state.payload.error ?? `World ${state.payload.world} is not loaded on the server.`,
      };
    case "not_ready":
      return {
        title: "Navigation is not ready for this world yet.",
        detail: "The route graph is still being built or the world is not loaded. Try again shortly.",
      };
    case "network_error":
      return {
        title: "Could not reach the route service.",
        detail: `Check that the server is running and reachable (${state.message}).`,
      };
    case "invalid_payload":
      return {
        title: "The route service returned an unexpected response.",
        detail: state.message,
      };
    default:
      return null;
  }
}

export default function NavApp() {
  const [worlds, setWorlds] = useState<SettingsWorld[]>([]);
  const [worldName, setWorldName] = useState<string | null>(null);
  const [worldSettings, setWorldSettings] = useState<WorldSettings>(FALLBACK_WORLD_SETTINGS);
  const [settingsError, setSettingsError] = useState<string | null>(null);

  const [fromText, setFromText] = useState("");
  const [toText, setToText] = useState("");
  const [target, setTarget] = useState<Target>("from");
  const [state, setState] = useState<RequestState>({ kind: "idle" });
  const [selectedStep, setSelectedStep] = useState<number | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  // Root settings.json -> world list.
  useEffect(() => {
    const ac = new AbortController();
    getJson(`${SQUAREMAP_URL}/tiles/settings.json`, ac.signal)
      .then((json) => {
        if (!isSettings(json)) throw new Error("settings.json has an unexpected shape");
        const sorted = sortWorlds(json.worlds);
        setWorlds(sorted);
        setWorldName((cur) => cur ?? sorted[0]?.name ?? null);
      })
      .catch((e: unknown) => {
        if (ac.signal.aborted) return;
        setSettingsError(`Could not load squaremap settings: ${e instanceof Error ? e.message : String(e)}`);
      });
    return () => ac.abort();
  }, []);

  // Per-world settings.json.
  useEffect(() => {
    if (!worldName) return;
    const ac = new AbortController();
    getJson(`${SQUAREMAP_URL}/tiles/${worldName}/settings.json`, ac.signal)
      .then((json) => {
        if (!isWorldSettings(json)) throw new Error("world settings.json has an unexpected shape");
        setWorldSettings(json);
      })
      .catch((e: unknown) => {
        if (ac.signal.aborted) return;
        setWorldSettings(FALLBACK_WORLD_SETTINGS);
        setSettingsError(`Could not load settings for ${worldName}: ${e instanceof Error ? e.message : String(e)}`);
      });
    return () => ac.abort();
  }, [worldName]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const payload = state.kind === "ok" ? state.payload : null;
  const steps: Step[] = useMemo(() => (payload ? buildDirections(payload.points) : []), [payload]);
  const highlight = selectedStep !== null ? (steps[selectedStep]?.segments ?? null) : null;

  const fromPos = parseCoordInput(fromText);
  const toPos = parseCoordInput(toText);

  const onMapClick = useCallback(
    (block: { x: number; z: number }) => {
      if (target === "from") {
        setFromText(formatCoord(block));
        setTarget("to");
      } else {
        setToText(formatCoord(block));
      }
    },
    [target],
  );

  const changeWorld = (name: string) => {
    abortRef.current?.abort();
    setWorldName(name);
    setSettingsError(null);
    setState({ kind: "idle" });
    setSelectedStep(null);
  };

  const swap = () => {
    setFromText(toText);
    setToText(fromText);
  };

  const getDirections = async () => {
    const from = parseCoordInput(fromText);
    const to = parseCoordInput(toText);
    if (!from || !to) {
      abortRef.current?.abort();
      setState({
        kind: "input_error",
        message: `Enter ${!from ? "a start" : "a destination"} as "x, z" (whole block numbers), or click the map.`,
      });
      return;
    }
    if (!worldName) {
      setState({ kind: "input_error", message: "No world is selected." });
      return;
    }
    abortRef.current?.abort();
    const ac = new AbortController();
    abortRef.current = ac;
    setSelectedStep(null);
    setState({ kind: "loading" });
    try {
      const result = await fetchRoute(buildRouteUrl(ROUTE_API, from, to, webNameToWorldId(worldName)), ac.signal);
      if (!ac.signal.aborted) setState(result);
    } catch {
      // Aborted by a newer request or a world change: the newer state wins.
    }
  };

  const failure = failureMessage(state);
  const worldSettingsKey = `${worldName ?? ""}|${worldSettings.zoom.max}|${worldSettings.spawn.x}|${worldSettings.spawn.z}`;

  return (
    <div className={styles.root}>
      <main className={styles.mapArea}>
        <MapView
          tileTemplate={worldName ? tileUrlTemplate(SQUAREMAP_URL, worldName) : null}
          worldKey={worldSettingsKey}
          zoom={worldSettings.zoom}
          spawn={worldSettings.spawn}
          routePoints={payload ? payload.points : null}
          highlight={highlight}
          from={fromPos}
          to={toPos}
          onMapClick={onMapClick}
        />
      </main>

      <aside className={`${styles.panel} ${collapsed ? styles.collapsed : ""}`} aria-label="Directions">
        <div className={styles.panelHeader}>
          <h1 className={styles.title}>Directions</h1>
          <button
            type="button"
            className={styles.collapseButton}
            aria-expanded={!collapsed}
            aria-controls="directions-body"
            onClick={() => setCollapsed((c) => !c)}
          >
            {collapsed ? "Show" : "Hide"}
          </button>
        </div>

        <div id="directions-body" className={styles.panelBody} hidden={collapsed}>
          <form
            className={styles.form}
            onSubmit={(e) => {
              e.preventDefault();
              void getDirections();
            }}
          >
            <label className={styles.field}>
              <span>World</span>
              <select
                value={worldName ?? ""}
                onChange={(e) => changeWorld(e.target.value)}
                disabled={worlds.length === 0}
              >
                {worlds.length === 0 && <option value="">Loading worlds…</option>}
                {worlds.map((w) => (
                  <option key={w.name} value={w.name}>
                    {w.display_name}
                  </option>
                ))}
              </select>
            </label>

            <div className={styles.endpoints}>
              <div className={styles.endpointInputs}>
                <label className={styles.field} htmlFor="from-input">
                  <span>From (x, z)</span>
                </label>
                <input
                  id="from-input"
                  inputMode="numeric"
                  placeholder="e.g. 12, -40"
                  value={fromText}
                  onChange={(e) => setFromText(e.target.value)}
                  aria-invalid={fromText !== "" && !fromPos}
                  autoComplete="off"
                />
                <label className={styles.field} htmlFor="to-input">
                  <span>To (x, z)</span>
                </label>
                <input
                  id="to-input"
                  inputMode="numeric"
                  placeholder="e.g. 310, 95"
                  value={toText}
                  onChange={(e) => setToText(e.target.value)}
                  aria-invalid={toText !== "" && !toPos}
                  autoComplete="off"
                />
              </div>
              <button type="button" className={styles.swap} onClick={swap} aria-label="Swap start and destination" title="Swap">
                ⇅
              </button>
            </div>

            <fieldset className={styles.pick}>
              <legend>Map click sets</legend>
              <label>
                <input type="radio" name="pick" checked={target === "from"} onChange={() => setTarget("from")} /> From
              </label>
              <label>
                <input type="radio" name="pick" checked={target === "to"} onChange={() => setTarget("to")} /> To
              </label>
            </fieldset>

            <button type="submit" className={styles.primary} disabled={state.kind === "loading"}>
              {state.kind === "loading" ? "Finding route…" : "Get directions"}
            </button>
          </form>

          <div aria-live="polite" className={styles.status}>
            {settingsError && <p className={styles.warn}>{settingsError}</p>}
            {state.kind === "loading" && <p className={styles.info}>Finding a route…</p>}
            {failure && (
              <div className={styles.error} role="alert" data-state={state.kind}>
                <p className={styles.errorTitle}>{failure.title}</p>
                {failure.detail && <p className={styles.errorDetail}>{failure.detail}</p>}
              </div>
            )}
          </div>

          {payload && (
            <section aria-label="Route summary and steps">
              <dl className={styles.summary}>
                <div>
                  <dt>Distance</dt>
                  <dd>{payload.distance.toFixed(1)} blocks</dd>
                </div>
                <div>
                  <dt>Walking time</dt>
                  <dd>≈ {formatDuration(walkingSeconds(payload.distance))}</dd>
                </div>
                <div>
                  <dt>Waypoints</dt>
                  <dd>{payload.points.length}</dd>
                </div>
              </dl>
              <ol className={styles.steps}>
                {steps.map((s, i) => (
                  <li key={i}>
                    <button
                      type="button"
                      className={`${styles.step} ${selectedStep === i ? styles.stepSelected : ""}`}
                      aria-pressed={selectedStep === i}
                      onClick={() => setSelectedStep(selectedStep === i ? null : i)}
                    >
                      <span className={styles.stepText}>{s.text}</span>
                      {s.kind !== "arrive" && <span className={styles.stepDist}>{s.distance.toFixed(1)} blocks</span>}
                    </button>
                  </li>
                ))}
              </ol>
            </section>
          )}

          <p className={styles.footnote}>
            {USING_MOCK_SQUAREMAP ? "Mock squaremap settings" : `squaremap: ${SQUAREMAP_URL}`} ·{" "}
            {USING_MOCK_ROUTE_API ? "mock route API" : `route API: ${ROUTE_API}`}
          </p>
        </div>
      </aside>
    </div>
  );
}
