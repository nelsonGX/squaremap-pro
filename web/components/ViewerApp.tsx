"use client";

/**
 * Map viewer: full-screen squaremap tiles + drawn features, with a left side panel (bottom sheet on
 * narrow screens) for search, world switching, layer toggles and the feature info card.
 */
import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useState } from "react";
import { createApiClient, type ApiClient } from "../lib/api";
import type { Feature, FeatureType, World } from "../lib/api/types";
import { FEATURE_TYPES } from "../lib/api/types";
import { TILES_BASE, USE_FIXTURES } from "../lib/config";
import { ALL_VISIBLE, countByType, visibleFeatures, type LayerVisibility } from "../lib/features/layers";
import { searchFeatures } from "../lib/features/search";
import {
  CATEGORY_COLOURS,
  displayName,
  featureSubtitle,
  railwayColourMap,
  ROAD_LOOKS,
  TYPE_PLURAL_LABELS,
  UNKNOWN_RAILWAY_COLOUR,
} from "../lib/features/styles";
import {
  DEFAULT_WORLD_SETTINGS,
  loadWorldSettings,
  worldSettingsUrl,
  worldTileTemplate,
  type WorldSettings,
} from "../lib/squaremapSettings";
import FeatureCard from "./FeatureCard";
import type { FlyRequest } from "./MapView";
import styles from "./ViewerApp.module.css";

const MapView = dynamic(() => import("./MapView"), {
  ssr: false,
  loading: () => <div className={styles.mapLoading}>Loading map…</div>,
});

const LAYER_SWATCH: Record<FeatureType, string> = {
  building: CATEGORY_COLOURS.residential,
  road: ROAD_LOOKS.highway.color,
  railway: "#d62828",
  station: "#ffffff",
};

type Load<T> = { status: "loading" } | { status: "ok"; value: T } | { status: "error"; message: string };

interface WorldFeatures {
  worldId: string;
  features: Feature[];
  skipped: number;
}

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === "AbortError";
}

/** Feature colour for the result list dot. */
function featureDotColour(f: Feature, railwayColours: ReadonlyMap<string, string>): string {
  switch (f.type) {
    case "building":
      return CATEGORY_COLOURS[f.props.category];
    case "road":
      return ROAD_LOOKS[f.props.roadClass].color;
    case "railway":
      return f.props.colour;
    case "station":
      return railwayColours.get(f.props.railwayId) ?? UNKNOWN_RAILWAY_COLOUR;
  }
}

export default function ViewerApp() {
  const [api] = useState<ApiClient>(() => createApiClient());
  const [reloadWorlds, setReloadWorlds] = useState(0);
  const [reloadFeatures, setReloadFeatures] = useState(0);

  const [worlds, setWorlds] = useState<Load<World[]>>({ status: "loading" });
  const [worldId, setWorldId] = useState<string | null>(null);
  const [settings, setSettings] = useState<{ worldId: string; value: WorldSettings; warning: string | null } | null>(null);
  const [featureLoad, setFeatureLoad] = useState<Load<WorldFeatures>>({ status: "loading" });

  const [visibility, setVisibility] = useState<LayerVisibility>(ALL_VISIBLE);
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  /** Card opened from the result list: show "back to results". */
  const [fromResults, setFromResults] = useState(false);
  const [flyRequest, setFlyRequest] = useState<FlyRequest | null>(null);
  const [sheetOpen, setSheetOpen] = useState(true);

  // Worlds.
  useEffect(() => {
    const ac = new AbortController();
    api
      .listWorlds(ac.signal)
      .then((list) => {
        setWorlds({ status: "ok", value: list });
        setWorldId((cur) =>
          cur && list.some((w) => w.id === cur)
            ? cur
            : (list.find((w) => w.id === "minecraft:overworld") ?? list[0])?.id ?? null,
        );
      })
      .catch((e: unknown) => {
        if (!isAbort(e)) setWorlds({ status: "error", message: errorMessage(e) });
      });
    return () => ac.abort();
  }, [api, reloadWorlds]);

  // squaremap per-world settings (zoom levels, spawn). Fixtures have no tiles: use defaults.
  useEffect(() => {
    if (!worldId || USE_FIXTURES) return;
    const ac = new AbortController();
    loadWorldSettings(worldSettingsUrl(TILES_BASE, worldId), ac.signal)
      .then((r) => setSettings({ worldId, value: r.settings, warning: r.warning }))
      .catch(() => {
        // aborted by a world change
      });
    return () => ac.abort();
  }, [worldId]);

  // Features of the selected world.
  useEffect(() => {
    if (!worldId) return;
    const ac = new AbortController();
    api
      .listFeatures(worldId, ac.signal)
      .then((r) => setFeatureLoad({ status: "ok", value: { worldId, features: r.features, skipped: r.skipped.length } }))
      .catch((e: unknown) => {
        if (!isAbort(e)) setFeatureLoad({ status: "error", message: errorMessage(e) });
      });
    return () => ac.abort();
  }, [api, worldId, reloadFeatures]);

  const allFeatures = useMemo(
    () => (featureLoad.status === "ok" && featureLoad.value.worldId === worldId ? featureLoad.value.features : []),
    [featureLoad, worldId],
  );
  const shown = useMemo(() => visibleFeatures(allFeatures, visibility), [allFeatures, visibility]);
  const counts = useMemo(() => countByType(allFeatures), [allFeatures]);
  const results = useMemo(() => searchFeatures(allFeatures, query), [allFeatures, query]);
  const railwayColours = useMemo(() => railwayColourMap(allFeatures), [allFeatures]);
  const railwayNames = useMemo(
    () => new Map(allFeatures.filter((f) => f.type === "railway").map((f) => [f.id, displayName(f)])),
    [allFeatures],
  );
  const selected = selectedId ? (allFeatures.find((f) => f.id === selectedId) ?? null) : null;

  const worldSettings = settings && settings.worldId === worldId ? settings.value : DEFAULT_WORLD_SETTINGS;
  const featuresLoading =
    worldId !== null && (featureLoad.status === "loading" || (featureLoad.status === "ok" && featureLoad.value.worldId !== worldId));

  const openFeature = useCallback((id: string, opts: { fly: boolean; fromResults: boolean }) => {
    setSelectedId(id);
    setFromResults(opts.fromResults);
    setSheetOpen(true);
    if (opts.fly) setFlyRequest((prev) => ({ featureId: id, nonce: (prev?.nonce ?? 0) + 1 }));
  }, []);

  const onMapSelect = useCallback(
    (id: string | null) => {
      if (id === null) setSelectedId(null);
      else openFeature(id, { fly: false, fromResults: false });
    },
    [openFeature],
  );

  const openFromList = (f: Feature, viaResults: boolean) => {
    // A result of a hidden type turns its layer on so the user can see it.
    setVisibility((v) => (v[f.type] ? v : { ...v, [f.type]: true }));
    openFeature(f.id, { fly: true, fromResults: viaResults });
  };

  const changeWorld = (id: string) => {
    setWorldId(id);
    setFeatureLoad({ status: "loading" });
    setSelectedId(null);
    setQuery("");
  };

  const showResults = query.trim() !== "" && !selected;
  const worldList = worlds.status === "ok" ? worlds.value : [];

  return (
    <div className={styles.root}>
      <main className={styles.mapArea}>
        <MapView
          tileTemplate={worldId && !USE_FIXTURES ? worldTileTemplate(TILES_BASE, worldId) : null}
          worldKey={`${worldId ?? ""}|${worldSettings.zoom.max}|${worldSettings.spawn.x}|${worldSettings.spawn.z}`}
          zoom={worldSettings.zoom}
          spawn={worldSettings.spawn}
          features={shown}
          allFeatures={allFeatures}
          selectedId={selectedId}
          flyRequest={flyRequest}
          onSelect={onMapSelect}
        />
      </main>

      <aside className={`${styles.panel} ${sheetOpen ? "" : styles.sheetClosed}`} aria-label="Map panel">
        <button
          type="button"
          className={styles.sheetHandle}
          onClick={() => setSheetOpen((o) => !o)}
          aria-expanded={sheetOpen}
          aria-controls="panel-body"
          aria-label={sheetOpen ? "Collapse panel" : "Expand panel"}
        >
          <span />
        </button>

        <div className={styles.searchRow} role="search">
          <input
            type="search"
            className={styles.search}
            placeholder="Search buildings, roads, railways, stations"
            aria-label="Search features by name"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setSelectedId(null);
              setSheetOpen(true);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && results[0]) openFromList(results[0], true);
              if (e.key === "Escape") setQuery("");
            }}
            autoComplete="off"
            spellCheck={false}
          />
        </div>

        <div id="panel-body" className={styles.panelBody}>
          <div className={styles.controls}>
            <label className={styles.worldSelect}>
              <span className={styles.srOnly}>World</span>
              <select
                value={worldId ?? ""}
                onChange={(e) => changeWorld(e.target.value)}
                disabled={worldList.length === 0}
              >
                {worldList.length === 0 && <option value="">{worlds.status === "loading" ? "Loading worlds…" : "No worlds"}</option>}
                {worldList.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name && w.name !== w.id ? `${w.name} (${w.id})` : w.id}
                  </option>
                ))}
              </select>
            </label>
            <div className={styles.chips} role="group" aria-label="Layers">
              {FEATURE_TYPES.map((t) => (
                <button
                  key={t}
                  type="button"
                  className={styles.chip}
                  aria-pressed={visibility[t]}
                  onClick={() => setVisibility((v) => ({ ...v, [t]: !v[t] }))}
                >
                  <span className={styles.chipDot} style={{ background: LAYER_SWATCH[t] }} data-type={t} aria-hidden="true" />
                  {TYPE_PLURAL_LABELS[t]}
                  <span className={styles.chipCount}>{counts[t]}</span>
                </button>
              ))}
            </div>
          </div>

          <div aria-live="polite">
            {worlds.status === "error" && (
              <div className={styles.error} role="alert">
                <p>Could not load worlds. {worlds.message}</p>
                <button
                  type="button"
                  className={styles.textButton}
                  onClick={() => {
                    setWorlds({ status: "loading" });
                    setReloadWorlds((n) => n + 1);
                  }}
                >
                  Retry
                </button>
              </div>
            )}
            {featureLoad.status === "error" && (
              <div className={styles.error} role="alert">
                <p>Could not load map features. {featureLoad.message}</p>
                <button
                  type="button"
                  className={styles.textButton}
                  onClick={() => {
                    setFeatureLoad({ status: "loading" });
                    setReloadFeatures((n) => n + 1);
                  }}
                >
                  Retry
                </button>
              </div>
            )}
            {featuresLoading && <p className={styles.muted}>Loading features…</p>}
            {featureLoad.status === "ok" && featureLoad.value.skipped > 0 && (
              <p className={styles.warn}>
                {featureLoad.value.skipped} feature{featureLoad.value.skipped === 1 ? "" : "s"} could not be displayed
                (unexpected data).
              </p>
            )}
            {settings?.warning && settings.worldId === worldId && <p className={styles.warn}>{settings.warning}</p>}
          </div>

          {selected ? (
            <FeatureCard
              feature={selected}
              allFeatures={allFeatures}
              onClose={() => setSelectedId(null)}
              onOpen={(id) => {
                const f = allFeatures.find((x) => x.id === id);
                if (f) openFromList(f, false);
              }}
              onBack={fromResults && query.trim() !== "" ? () => setSelectedId(null) : undefined}
            />
          ) : showResults ? (
            <section aria-label="Search results">
              {results.length === 0 ? (
                <p className={styles.muted}>No features named “{query.trim()}”.</p>
              ) : (
                <ul className={styles.results}>
                  {results.map((f) => (
                    <li key={f.id}>
                      <button type="button" className={styles.result} onClick={() => openFromList(f, true)}>
                        <span
                          className={styles.resultDot}
                          style={{ background: featureDotColour(f, railwayColours) }}
                          aria-hidden="true"
                        />
                        <span className={styles.resultText}>
                          <span className={styles.resultName}>{displayName(f)}</span>
                          <span className={styles.resultSub}>{featureSubtitle(f, railwayNames)}</span>
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ) : (
            featureLoad.status === "ok" &&
            !featuresLoading && (
              <p className={styles.hint}>
                {allFeatures.length === 0
                  ? "Nothing has been mapped in this world yet."
                  : "Click a feature on the map or search by name to see its details."}
              </p>
            )
          )}

          {USE_FIXTURES && <p className={styles.footnote}>Fixture data (NEXT_PUBLIC_USE_FIXTURES=1) · no tiles</p>}
        </div>
      </aside>
    </div>
  );
}
