"use client";

/**
 * Map viewer + editor: full-screen squaremap tiles + drawn features, with a left side panel (bottom
 * sheet on narrow screens) for search, world switching, layer toggles, the feature info card and —
 * for players with edit permission — the editor.
 */
import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useReducer, useState } from "react";
import { createApiClient, type ApiClient } from "../lib/api";
import type { AuthMe, Feature, FeatureType, World } from "../lib/api/types";
import { FEATURE_TYPES } from "../lib/api/types";
import { TILES_BASE, USE_FIXTURES } from "../lib/config";
import { parseAuthQuery } from "../lib/editor/authQuery";
import {
  draftStyle,
  draftToInput,
  editorReducer,
  INITIAL_EDITOR_STATE,
  isDirty,
} from "../lib/editor/draft";
import { isAuthFailure, saveFailureOf, SESSION_LOST_MESSAGE } from "../lib/editor/errors";
import {
  placeStation,
  positionsToGeometry,
  SNAP_DISTANCE_PX,
  snapTargets,
  STATION_SNAP_DISTANCE_PX,
} from "../lib/editor/geometry";
import { validateInput } from "../lib/editor/validate";
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
import EditorPanel from "./EditorPanel";
import FeatureCard from "./FeatureCard";
import type { FlyRequest, MapDraft, MapEditing } from "./MapView";
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
  const [reloadAuth, setReloadAuth] = useState(0);

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

  const [auth, setAuth] = useState<AuthMe | null>(null);
  const [authMessage, setAuthMessage] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [editor, dispatch] = useReducer(editorReducer, INITIAL_EDITOR_STATE);
  /** Action waiting for "Discard changes" confirmation. */
  const [pendingAction, setPendingAction] = useState<(() => void) | null>(null);
  const [mapHint, setMapHint] = useState<string | null>(null);
  /** Bumped to (re)start geoman draw mode for a draft without geometry. */
  const [drawToken, setDrawToken] = useState(0);

  const dirty = isDirty(editor.draft);
  const canEdit = auth?.loggedIn === true && auth.canEdit;

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

  // Login state; on first load also handle `?edit=1` / `?authError=` from the redeem redirect.
  useEffect(() => {
    const ac = new AbortController();
    const handleQuery = (me: AuthMe | null) => {
      const q = parseAuthQuery(window.location.search);
      if (!q.changed) return;
      window.history.replaceState(window.history.state, "", `${window.location.pathname}${q.cleanedSearch}${window.location.hash}`);
      if (q.authError) setAuthMessage(q.authError);
      if (q.edit) {
        if (me?.loggedIn && me.canEdit) setEditMode(true);
        else if (me?.loggedIn) setAuthMessage("Your account does not have edit permission.");
        else setAuthMessage("Login did not complete — run /mapedit again.");
      }
    };
    api
      .me(ac.signal)
      .then((me) => {
        setAuth(me);
        if (!(me.loggedIn && me.canEdit)) setEditMode(false);
        handleQuery(me);
      })
      .catch((e: unknown) => {
        if (isAbort(e)) return;
        setAuth({ loggedIn: false });
        setEditMode(false);
        handleQuery(null);
      });
    return () => ac.abort();
  }, [api, reloadAuth]);

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

  // Warn before leaving the page with unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  const allFeatures = useMemo(
    () => (featureLoad.status === "ok" && featureLoad.value.worldId === worldId ? featureLoad.value.features : []),
    [featureLoad, worldId],
  );
  const editingId = editMode ? (editor.draft?.featureId ?? null) : null;
  const shown = useMemo(
    () => visibleFeatures(allFeatures, visibility).filter((f) => f.id !== editingId),
    [allFeatures, visibility, editingId],
  );
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

  /** Replace/insert (or remove, with `remove`) a feature in the loaded list of `world`. */
  const updateLocalFeatures = useCallback((world: string, feature: Feature | null, removeId?: string) => {
    setFeatureLoad((load) => {
      if (load.status !== "ok" || load.value.worldId !== world) return load;
      let features = load.value.features.filter((f) => f.id !== (removeId ?? feature?.id));
      if (feature) {
        const i = load.value.features.findIndex((f) => f.id === feature.id);
        features = [...load.value.features];
        if (i >= 0) features[i] = feature;
        else features.push(feature);
      }
      return { status: "ok", value: { ...load.value, features } };
    });
  }, []);

  /** Runs `action` now, or after the user confirms discarding unsaved changes. */
  const guard = (action: () => void) => {
    if (editMode && dirty) setPendingAction(() => action);
    else action();
  };

  const flyTo = (id: string) => setFlyRequest((prev) => ({ featureId: id, nonce: (prev?.nonce ?? 0) + 1 }));

  const startEdit = (f: Feature) => {
    setPendingAction(null);
    setMapHint(null);
    dispatch({ type: "start_edit", feature: f });
    setSelectedId(f.id);
    setSheetOpen(true);
  };

  const openFeature = (f: Feature, opts: { fly: boolean; fromResults: boolean }) => {
    if (editMode) {
      if (editor.draft?.featureId === f.id) return;
      guard(() => {
        startEdit(f);
        if (opts.fly) flyTo(f.id);
      });
      return;
    }
    setSelectedId(f.id);
    setFromResults(opts.fromResults);
    setSheetOpen(true);
    if (opts.fly) flyTo(f.id);
  };

  const onMapSelect = (id: string | null) => {
    if (id === null) {
      if (editMode) {
        // Clicking empty map closes a clean draft; a dirty one stays open.
        if (editor.draft && !dirty && editor.phase === "idle" && editor.draft.featureId !== null) {
          dispatch({ type: "close" });
          setSelectedId(null);
        }
        return;
      }
      setSelectedId(null);
      return;
    }
    const f = allFeatures.find((x) => x.id === id);
    if (f) openFeature(f, { fly: false, fromResults: false });
  };

  const openFromList = (f: Feature, viaResults: boolean) => {
    // A result of a hidden type turns its layer on so the user can see it.
    setVisibility((v) => (v[f.type] ? v : { ...v, [f.type]: true }));
    openFeature(f, { fly: true, fromResults: viaResults });
  };

  const changeWorld = (id: string) =>
    guard(() => {
      dispatch({ type: "close" });
      setPendingAction(null);
      setWorldId(id);
      setFeatureLoad({ status: "loading" });
      setSelectedId(null);
      setQuery("");
    });

  const leaveEditMode = () =>
    guard(() => {
      dispatch({ type: "close" });
      setPendingAction(null);
      setMapHint(null);
      setEditMode(false);
      setSelectedId(null);
    });

  const enterEditMode = () => {
    setEditMode(true);
    setSelectedId(null);
    setAuthMessage(null);
  };

  const sessionLost = () => {
    dispatch({ type: "close" });
    setPendingAction(null);
    setEditMode(false);
    setSelectedId(null);
    setAuthMessage(SESSION_LOST_MESSAGE);
    setReloadAuth((n) => n + 1);
  };

  const logout = () =>
    guard(() => {
      dispatch({ type: "close" });
      setPendingAction(null);
      setEditMode(false);
      api
        .logout()
        .then(() => {
          setAuth({ loggedIn: false });
          setAuthMessage(null);
        })
        .catch((e: unknown) => setAuthMessage(`Could not log out: ${saveFailureOf(e).message}`));
    });

  // ---- editor actions ----

  const addFeature = (type: FeatureType) =>
    guard(() => {
      setPendingAction(null);
      setMapHint(null);
      setSelectedId(null);
      if (type === "station") setVisibility((v) => ({ ...v, railway: true }));
      dispatch({ type: "start_create", featureType: type });
      setDrawToken((n) => n + 1);
    });

  const draft = editMode ? editor.draft : null;

  const onGeometry = (positions: { x: number; z: number }[], bpp: number) => {
    if (!draft || draft.type === "station") return;
    const geometry = positionsToGeometry(draft.type, positions, snapTargets(allFeatures, draft.featureId), SNAP_DISTANCE_PX * bpp);
    if (geometry.length === 0) {
      setDrawToken((n) => n + 1);
      return;
    }
    dispatch({ type: "set_geometry", geometry });
  };

  const onPlace = (position: { x: number; z: number }, bpp: number) => {
    if (!draft || draft.type !== "station" || editor.phase !== "idle") return;
    const placement = placeStation(position, allFeatures, STATION_SNAP_DISTANCE_PX * bpp);
    if (!placement) {
      setMapHint("That is not a railway point. Zoom in and click on a vertex of a railway line.");
      return;
    }
    setMapHint(null);
    const railwayId = placement.railwayIds.includes(draft.fields.railwayId) ? draft.fields.railwayId : placement.railwayIds[0]!;
    dispatch({ type: "place_station", vertex: placement.vertex, railwayId });
  };

  const save = async () => {
    if (!draft || !worldId || editor.phase !== "idle") return;
    const world = worldId;
    const input = draftToInput(draft);
    const lookup = (id: string) => allFeatures.find((f) => f.id === id && f.type === "railway")?.geometry;
    if (validateInput(input, lookup).length > 0) {
      dispatch({ type: "save_invalid" });
      return;
    }
    dispatch({ type: "save_start" });
    try {
      const saved =
        draft.featureId !== null && draft.revision !== null
          ? await api.updateFeature(world, draft.featureId, { ...input, revision: draft.revision })
          : await api.createFeature(world, input);
      updateLocalFeatures(world, saved);
      dispatch({ type: "save_ok", feature: saved, created: draft.featureId === null });
      setSelectedId(saved.id);
    } catch (e) {
      const failure = saveFailureOf(e);
      if (isAuthFailure(failure.status)) {
        sessionLost();
        return;
      }
      if (failure.status === 409 && failure.current) updateLocalFeatures(world, failure.current);
      if (failure.status === 404 && draft.featureId) updateLocalFeatures(world, null, draft.featureId);
      dispatch({ type: "save_error", failure });
    }
  };

  const remove = async () => {
    if (!draft || draft.featureId === null || draft.revision === null || !worldId) return;
    const world = worldId;
    const id = draft.featureId;
    dispatch({ type: "delete_start" });
    try {
      await api.deleteFeature(world, id, draft.revision);
      updateLocalFeatures(world, null, id);
      dispatch({ type: "delete_ok" });
      setSelectedId(null);
    } catch (e) {
      const failure = saveFailureOf(e);
      if (isAuthFailure(failure.status)) {
        sessionLost();
        return;
      }
      if (failure.status === 409 && failure.current) updateLocalFeatures(world, failure.current);
      dispatch({ type: "save_error", failure });
    }
  };

  const reloadLatest = async () => {
    const notice = editor.notice;
    if (!draft || !worldId || notice?.kind !== "conflict" || draft.featureId === null) return;
    const world = worldId;
    const id = draft.featureId;
    let latest = notice.current;
    if (!latest) {
      try {
        const list = await api.listFeatures(world);
        latest = list.features.find((f) => f.id === id) ?? null;
        setFeatureLoad({ status: "ok", value: { worldId: world, features: list.features, skipped: list.skipped.length } });
      } catch (e) {
        dispatch({ type: "save_error", failure: { ...saveFailureOf(e), status: 0 } });
        return;
      }
    }
    dispatch({ type: "reload_latest", feature: latest });
    if (!latest) setSelectedId(null);
    setDrawToken((n) => n + 1);
  };

  const mapDraft: MapDraft | null = useMemo(
    () =>
      draft
        ? { key: draft.key * 100_000 + drawToken, type: draft.type, featureId: draft.featureId, geometry: draft.geometry, style: draftStyle(draft, railwayColours) }
        : null,
    [draft, drawToken, railwayColours],
  );
  // Handlers read fresh state through MapView's ref; the object identity only matters for the draft.
  const editing: MapEditing | null = editMode ? { draft: mapDraft, onGeometry, onPlace } : null;

  const showResults = query.trim() !== "" && !selected && !editMode;
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
          selectedId={editMode ? null : selectedId}
          flyRequest={flyRequest}
          onSelect={onMapSelect}
          editing={editing}
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
              if (!editMode) setSelectedId(null);
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
          {(auth?.loggedIn || authMessage) && (
            <div className={styles.accountRow}>
              {auth?.loggedIn && (
                <>
                  <span className={styles.accountName} title={auth.uuid}>
                    {auth.name}
                  </span>
                  {canEdit ? (
                    <button
                      type="button"
                      className={styles.editToggle}
                      aria-pressed={editMode}
                      onClick={editMode ? leaveEditMode : enterEditMode}
                    >
                      {editMode ? "Done editing" : "Edit map"}
                    </button>
                  ) : (
                    <span className={styles.muted}>View only</span>
                  )}
                  <button type="button" className={styles.textButton} onClick={logout}>
                    Log out
                  </button>
                </>
              )}
              {authMessage && (
                <p className={styles.warn} role="alert">
                  {authMessage}{" "}
                  <button type="button" className={styles.textButton} onClick={() => setAuthMessage(null)} aria-label="Dismiss">
                    ×
                  </button>
                </p>
              )}
            </div>
          )}

          <div className={styles.controls}>
            <label className={styles.worldSelect}>
              <span className={styles.srOnly}>World</span>
              <select value={worldId ?? ""} onChange={(e) => changeWorld(e.target.value)} disabled={worldList.length === 0}>
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

          {editMode ? (
            <>
              {query.trim() !== "" && results.length > 0 && (
                <ul className={styles.results} aria-label="Search results">
                  {results.slice(0, 8).map((f) => (
                    <li key={f.id}>
                      <button type="button" className={styles.result} onClick={() => openFromList(f, true)}>
                        <span className={styles.resultDot} style={{ background: featureDotColour(f, railwayColours) }} aria-hidden="true" />
                        <span className={styles.resultText}>
                          <span className={styles.resultName}>{displayName(f)}</span>
                          <span className={styles.resultSub}>{featureSubtitle(f, railwayNames)}</span>
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <EditorPanel
                state={editor}
                dispatch={dispatch}
                allFeatures={allFeatures}
                mapHint={mapHint}
                discardPrompt={pendingAction !== null}
                onConfirmDiscard={() => {
                  const action = pendingAction;
                  setPendingAction(null);
                  dispatch({ type: "close" });
                  action?.();
                }}
                onKeepEditing={() => setPendingAction(null)}
                onAdd={addFeature}
                onSave={() => void save()}
                onCancel={() => {
                  dispatch({ type: "close" });
                  setMapHint(null);
                  setSelectedId(null);
                }}
                onDelete={() => void remove()}
                onRedraw={() => {
                  dispatch({ type: "set_geometry", geometry: [] });
                  setDrawToken((n) => n + 1);
                }}
                onReloadLatest={() => void reloadLatest()}
              />
            </>
          ) : selected ? (
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

          <p className={styles.footnote}>
            {!auth?.loggedIn && "Editors: run /mapedit in game to get a login link. "}
            {USE_FIXTURES && "Fixture data (NEXT_PUBLIC_USE_FIXTURES=1) · no tiles"}
          </p>
        </div>
      </aside>
    </div>
  );
}
