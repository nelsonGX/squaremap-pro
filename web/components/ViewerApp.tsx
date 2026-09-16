"use client";

/**
 * Map viewer + editor, laid out like Google Maps: a full-screen map with floating chrome over it —
 * a search "omnibox" and category chips at the top left, a results / place / directions sheet under
 * them (a bottom sheet on narrow screens), the account button at the top right, the world picker at
 * the bottom left and round map controls at the bottom right.
 */
import dynamic from "next/dynamic";
import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";
import { createApiClient, type ApiClient } from "../lib/api";
import type { AuthMe, Feature, FeatureType, OnlinePlayer, World, XZ } from "../lib/api/types";
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
import { coordParam, featureAnchor, formatCoord, parseCoordInput } from "../lib/navigation/coords";
import { ALL_NAV_MODES, modesParam, routePoints, type NavModes } from "../lib/navigation/legs";
import { readNavUrlState, writeNavUrlState } from "../lib/navigation/urlState";
import { ALL_VISIBLE, countByType, visibleFeatures, type LayerVisibility } from "../lib/features/layers";
import { searchFeatures } from "../lib/features/search";
import {
  CATEGORY_COLOURS,
  DEFAULT_RAILWAY_COLOUR,
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
  headsUrlTemplate,
  markersRefreshMs,
  tilesRefreshMs,
  worldTileTemplate,
  type WorldSettings,
} from "../lib/squaremapSettings";
import { PLAYER_POLL_MS, playerColour, playersInWorld, playerTitle } from "../lib/players/players";
import { DirectionsHeader, DirectionsResults, type Endpoint, type RouteView, type Which } from "./DirectionsPanel";
import EditorPanel from "./EditorPanel";
import FeatureCard from "./FeatureCard";
import {
  CloseIcon,
  DirectionsIcon,
  LayersIcon,
  MenuIcon,
  PencilIcon,
  PeopleIcon,
  PersonIcon,
  SearchIcon,
} from "./icons";
import type { FlyRequest, MapDraft, MapEditing, MapNavigation } from "./MapView";
import styles from "./ViewerApp.module.css";

const MapView = dynamic(() => import("./MapView"), {
  ssr: false,
  loading: () => <div className={styles.mapLoading}>Loading map…</div>,
});

const LAYER_SWATCH: Record<FeatureType, string> = {
  building: CATEGORY_COLOURS.residential,
  road: ROAD_LOOKS.highway.color,
  railway: DEFAULT_RAILWAY_COLOUR,
  // A station is drawn as a white disc ringed in its line colour; the ring is the capsule's border.
  station: "#ffffff",
};

/** Stable empty list, so switching the player layer off does not re-render the marker effect. */
const EMPTY_PLAYERS: readonly OnlinePlayer[] = [];

type AppMode = "explore" | "directions" | "edit";

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

/**
 * Whether a freshly polled feature list is identical to the loaded one. Every write bumps a
 * feature's `revision`, so id + revision is enough and avoids deep-comparing geometry.
 */
function sameFeatures(a: WorldFeatures, b: WorldFeatures): boolean {
  return (
    a.worldId === b.worldId &&
    a.skipped === b.skipped &&
    a.features.length === b.features.length &&
    a.features.every((f, i) => f.id === b.features[i]!.id && f.revision === b.features[i]!.revision)
  );
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
  const [accountOpen, setAccountOpen] = useState(false);
  const [worldPickerOpen, setWorldPickerOpen] = useState(false);

  // ---- live player layer ----
  const [showPlayers, setShowPlayers] = useState(true);
  /** Last poll result, tagged with the world it came from so a world switch is not shown stale. */
  const [players, setPlayers] = useState<{ worldId: string | null; list: OnlinePlayer[]; max: number }>({
    worldId: null,
    list: [],
    max: 0,
  });
  const [playerListOpen, setPlayerListOpen] = useState(false);

  const [auth, setAuth] = useState<AuthMe | null>(null);
  const [authMessage, setAuthMessage] = useState<string | null>(null);
  const [mode, setMode] = useState<AppMode>("explore");
  const editMode = mode === "edit";
  const [editor, dispatch] = useReducer(editorReducer, INITIAL_EDITOR_STATE);
  /** Action waiting for "Discard changes" confirmation. */
  const [pendingAction, setPendingAction] = useState<(() => void) | null>(null);
  const [mapHint, setMapHint] = useState<string | null>(null);
  /** Bumped to (re)start geoman draw mode for a draft without geometry. */
  const [drawToken, setDrawToken] = useState(0);

  // ---- directions state ----
  const [navEnds, setNavEnds] = useState<Record<Which, Endpoint | null>>({ from: null, to: null });
  const [navTexts, setNavTexts] = useState<Record<Which, string>>({ from: "", to: "" });
  const [navModes, setNavModes] = useState<NavModes>(ALL_NAV_MODES);
  const [pickTarget, setPickTarget] = useState<Which | null>("from");
  const [routeResult, setRouteResult] = useState<{ key: string; view: RouteView } | null>(null);
  const [routeRetry, setRouteRetry] = useState(0);
  const [hoverLeg, setHoverLeg] = useState<number | null>(null);
  const [fitRequest, setFitRequest] = useState<{ points: XZ[]; nonce: number } | null>(null);
  /** The pending route request was caused by dragging a marker: do not refit the view. */
  const dragRef = useRef(false);
  const urlRestoredRef = useRef(false);
  const [urlReady, setUrlReady] = useState(false);

  const dirty = isDirty(editor.draft);
  const canEdit = auth?.loggedIn === true && auth.canEdit;

  // Worlds.
  useEffect(() => {
    const ac = new AbortController();
    api
      .listWorlds(ac.signal)
      .then((list) => {
        setWorlds({ status: "ok", value: list });
        let urlWorld: string | null = null;
        if (!urlRestoredRef.current) {
          // Restore shared directions (`?world=&from=&to=`) once.
          urlRestoredRef.current = true;
          const u = readNavUrlState(window.location.search);
          urlWorld = u.world && list.some((w) => w.id === u.world) ? u.world : null;
          if (u.from || u.to) {
            setNavEnds({ from: u.from ? { point: u.from, label: null } : null, to: u.to ? { point: u.to, label: null } : null });
            setNavTexts({ from: u.from ? formatCoord(u.from) : "", to: u.to ? formatCoord(u.to) : "" });
            setPickTarget(u.from && u.to ? null : u.from ? "to" : "from");
            setMode((m) => (m === "edit" ? m : "directions"));
          }
          setUrlReady(true);
        }
        setWorldId((cur) =>
          urlWorld ??
          (cur && list.some((w) => w.id === cur)
            ? cur
            : (list.find((w) => w.id === "minecraft:overworld") ?? list[0])?.id ?? null),
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
        if (me?.loggedIn && me.canEdit) setMode("edit");
        else if (me?.loggedIn) setAuthMessage("Your account does not have edit permission.");
        else setAuthMessage("Login did not complete — run /mapedit again.");
      }
    };
    api
      .me(ac.signal)
      .then((me) => {
        setAuth(me);
        if (!(me.loggedIn && me.canEdit)) setMode((m) => (m === "edit" ? "explore" : m));
        handleQuery(me);
      })
      .catch((e: unknown) => {
        if (isAbort(e)) return;
        setAuth({ loggedIn: false });
        setMode((m) => (m === "edit" ? "explore" : m));
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
      .then((r) =>
        setFeatureLoad((prev) => {
          const value = { worldId, features: r.features, skipped: r.skipped.length };
          // A poll that found nothing new keeps the old array, so the map layers are not rebuilt.
          return prev.status === "ok" && sameFeatures(prev.value, value) ? prev : { status: "ok", value };
        }),
      )
      .catch((e: unknown) => {
        if (!isAbort(e)) setFeatureLoad({ status: "error", message: errorMessage(e) });
      });
    return () => ac.abort();
  }, [api, worldId, reloadFeatures]);

  // Live players of the selected world, polled while the layer is on and the tab is visible.
  // A failed poll only empties the layer: it is decoration and must never surface an error.
  useEffect(() => {
    if (!worldId || !showPlayers) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let ac = new AbortController();
    const tick = () => {
      if (stopped) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        timer = setTimeout(tick, PLAYER_POLL_MS);
        return;
      }
      ac = new AbortController();
      api
        .listPlayers(worldId, ac.signal)
        .then((r) => {
          if (!stopped) setPlayers({ worldId, list: playersInWorld(r.players, worldId), max: r.max });
        })
        .catch((e: unknown) => {
          if (!stopped && !isAbort(e)) setPlayers({ worldId, list: [], max: 0 });
        })
        .finally(() => {
          if (!stopped) timer = setTimeout(tick, PLAYER_POLL_MS);
        });
    };
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
      ac.abort();
    };
  }, [api, worldId, showPlayers]);

  // Warn before leaving the page with unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  // Route requests: debounced; a newer request (or leaving directions) aborts the pending one.
  const navFrom = navEnds.from;
  const navTo = navEnds.to;
  const navKey =
    mode === "directions" && worldId && navFrom && navTo
      ? `${worldId}|${coordParam(navFrom.point)}|${coordParam(navTo.point)}|${navModes.road ? "r" : ""}${navModes.rail ? "t" : ""}|${routeRetry}`
      : null;
  useEffect(() => {
    if (!navKey || !worldId || !navFrom || !navTo) return;
    const ac = new AbortController();
    const req = { world: worldId, from: navFrom.point, to: navTo.point, modes: modesParam(navModes) };
    const timer = setTimeout(() => {
      api
        .route(req, ac.signal)
        .then((route) => {
          setRouteResult({ key: navKey, view: { kind: "result", route } });
          const fit = !dragRef.current;
          dragRef.current = false;
          if (fit && route.status === "ok") {
            setFitRequest((prev) => ({ points: routePoints(route), nonce: (prev?.nonce ?? 0) + 1 }));
          }
        })
        .catch((e: unknown) => {
          if (isAbort(e)) return;
          setRouteResult({ key: navKey, view: { kind: "error", message: saveFailureOf(e).message } });
        });
    }, 250);
    return () => {
      clearTimeout(timer);
      ac.abort();
    };
  }, [api, navKey, worldId, navFrom, navTo, navModes]);
  const routeView: RouteView = !navKey ? { kind: "idle" } : routeResult?.key === navKey ? routeResult.view : { kind: "loading" };

  // Shareable URL: `?world=&from=&to=` while in directions mode (other parameters are kept).
  useEffect(() => {
    if (!urlReady) return;
    const state =
      mode === "directions"
        ? { world: worldId, from: navEnds.from?.point ?? null, to: navEnds.to?.point ?? null }
        : { world: null, from: null, to: null };
    const next = writeNavUrlState(window.location.search, state);
    if (next !== window.location.search) {
      window.history.replaceState(window.history.state, "", `${window.location.pathname}${next}${window.location.hash}`);
    }
  }, [urlReady, mode, worldId, navEnds]);

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

  // Periodic feature refresh, on squaremap's own marker interval, so other editors' changes show up
  // without a reload. Paused while this browser has unsaved edits: the reload would race the draft.
  useEffect(() => {
    if (!worldId || dirty) return;
    const id = setInterval(() => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      setReloadFeatures((n) => n + 1);
    }, markersRefreshMs(worldSettings));
    return () => clearInterval(id);
  }, [worldId, dirty, worldSettings]);
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

  const switchMode = (next: AppMode) => {
    if (next === mode) return;
    const go = () => {
      dispatch({ type: "close" });
      setPendingAction(null);
      setMapHint(null);
      setSelectedId(null);
      setHoverLeg(null);
      if (next === "edit") setAuthMessage(null);
      setMode(next);
    };
    if (mode === "edit") guard(go);
    else go();
  };

  // ---- directions actions ----

  const setEndpoint = (which: Which, ep: Endpoint | null, opts: { advancePick: boolean; text?: string }) => {
    setNavEnds((cur) => ({ ...cur, [which]: ep }));
    if (opts.text !== undefined || ep) {
      setNavTexts((t) => ({ ...t, [which]: opts.text ?? (ep ? (ep.label ?? formatCoord(ep.point)) : "") }));
    }
    if (opts.advancePick && ep) setPickTarget(which === "from" && !navEnds.to ? "to" : null);
  };

  const onNavText = (which: Which, text: string) => {
    const p = parseCoordInput(text);
    setEndpoint(which, p ? { point: p, label: null } : null, { advancePick: false, text });
  };

  const onNavPickFeature = (which: Which, f: Feature) => {
    const other = navEnds[which === "from" ? "to" : "from"];
    const point = featureAnchor(f, other?.point);
    if (!point) return;
    setEndpoint(which, { point, label: displayName(f) }, { advancePick: true });
  };

  const onNavMapClick = (block: XZ) => {
    const target = pickTarget ?? (!navEnds.from ? "from" : !navEnds.to ? "to" : null);
    if (!target) return;
    setEndpoint(target, { point: block, label: null }, { advancePick: true });
  };

  const onNavDrag = (which: Which, block: XZ) => {
    dragRef.current = true;
    setEndpoint(which, { point: block, label: null }, { advancePick: false });
  };

  const swapEndpoints = () => {
    setNavEnds((e) => ({ from: e.to, to: e.from }));
    setNavTexts((t) => ({ from: t.to, to: t.from }));
  };

  const directionsTo = (f: Feature) => {
    const point = featureAnchor(f, navEnds.from?.point);
    if (!point) return;
    switchMode("directions");
    setNavEnds((e) => ({ ...e, to: { point, label: displayName(f) } }));
    setNavTexts((t) => ({ ...t, to: displayName(f) }));
    setPickTarget(navEnds.from ? null : "from");
  };

  const sessionLost = () => {
    dispatch({ type: "close" });
    setPendingAction(null);
    setMode((m) => (m === "edit" ? "explore" : m));
    setSelectedId(null);
    setAuthMessage(SESSION_LOST_MESSAGE);
    setReloadAuth((n) => n + 1);
  };

  const logout = () =>
    guard(() => {
      dispatch({ type: "close" });
      setPendingAction(null);
      setMode((m) => (m === "edit" ? "explore" : m));
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
  const shownRoute = routeView.kind === "result" && routeView.route.status === "ok" ? routeView.route : null;
  const navigation: MapNavigation | null =
    mode === "directions"
      ? {
          from: navFrom?.point ?? null,
          to: navTo?.point ?? null,
          route: shownRoute,
          highlightLeg: hoverLeg,
          onClick: onNavMapClick,
          onDrag: onNavDrag,
          onHoverLeg: setHoverLeg,
        }
      : null;

  const showResults = query.trim() !== "" && !selected && mode === "explore";
  const worldList = worlds.status === "ok" ? worlds.value : [];
  const currentWorld = worldList.find((w) => w.id === worldId) ?? null;
  const worldLabel = currentWorld ? (currentWorld.name || currentWorld.id) : worlds.status === "loading" ? "Loading…" : "No world";
  // Derived, not state: a world switch or a switched-off layer empties it without another render.
  const livePlayers = showPlayers && players.worldId === worldId ? players.list : EMPTY_PLAYERS;
  const notices =
    worlds.status === "error" ||
    featureLoad.status === "error" ||
    featuresLoading ||
    (featureLoad.status === "ok" && featureLoad.value.skipped > 0) ||
    (settings?.warning != null && settings.worldId === worldId) ||
    authMessage !== null;
  /** The sheet under the search bar has something to show. */
  const panelHasContent = mode !== "explore" || selected !== null || showResults || notices || playerListOpen;

  const openPlayerOnMap = (uuid: string) => {
    const p = livePlayers.find((x) => x.uuid === uuid);
    if (!p) return;
    setPlayerListOpen(true);
    setSheetOpen(true);
    setFitRequest((prev) => ({ points: [{ x: p.x, z: p.z }], nonce: (prev?.nonce ?? 0) + 1 }));
  };

  return (
    <div className={styles.root} data-sheet={panelHasContent && sheetOpen ? "open" : "closed"}>
      <main className={styles.mapArea}>
        <MapView
          tileTemplate={worldId && !USE_FIXTURES ? worldTileTemplate(TILES_BASE, worldId) : null}
          worldKey={`${worldId ?? ""}|${worldSettings.zoom.max}|${worldSettings.spawn.x}|${worldSettings.spawn.z}`}
          zoom={worldSettings.zoom}
          spawn={worldSettings.spawn}
          features={shown}
          allFeatures={allFeatures}
          selectedId={mode === "explore" ? selectedId : null}
          flyRequest={flyRequest}
          onSelect={onMapSelect}
          editing={editing}
          navigation={navigation}
          fitRequest={fitRequest}
          tilesRefreshMs={tilesRefreshMs(worldSettings)}
          players={livePlayers}
          headsUrl={USE_FIXTURES ? null : headsUrlTemplate(worldSettings)}
          onPlayerClick={openPlayerOnMap}
        />
      </main>

      {/* Left rail: search / directions header, category chips and the sheet beneath them. */}
      <div className={styles.leftRail}>
        {mode === "directions" ? (
          <DirectionsHeader
            texts={navTexts}
            endpoints={navEnds}
            pickTarget={pickTarget}
            modes={navModes}
            allFeatures={allFeatures}
            railwayNames={railwayNames}
            onText={onNavText}
            onPickFeature={onNavPickFeature}
            onPickTarget={setPickTarget}
            onSwap={swapEndpoints}
            onModes={setNavModes}
            onClose={() => switchMode("explore")}
          />
        ) : (
          <>
            <div className={styles.omnibox} role="search">
              <button
                type="button"
                className={styles.omniButton}
                onClick={() => setSheetOpen((o) => !o)}
                aria-expanded={sheetOpen}
                aria-controls="map-sheet"
                aria-label={sheetOpen ? "Hide the panel" : "Show the panel"}
                title="Panel"
              >
                <MenuIcon size={20} />
              </button>
              <input
                type="search"
                className={styles.omniInput}
                placeholder={editMode ? "Search a feature to edit" : "Search buildings, roads, railways"}
                aria-label="Search features by name"
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value);
                  if (!editMode) setSelectedId(null);
                  setSheetOpen(true);
                  setPlayerListOpen(false);
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && results[0]) openFromList(results[0], true);
                  if (e.key === "Escape") setQuery("");
                }}
                autoComplete="off"
                spellCheck={false}
              />
              {query === "" ? (
                <span className={styles.omniSearchIcon} aria-hidden="true">
                  <SearchIcon size={20} />
                </span>
              ) : (
                <button type="button" className={styles.omniButton} onClick={() => setQuery("")} aria-label="Clear search" title="Clear">
                  <CloseIcon size={20} />
                </button>
              )}
              <span className={styles.omniDivider} aria-hidden="true" />
              <button
                type="button"
                className={styles.omniDirections}
                onClick={() => switchMode("directions")}
                aria-label="Directions"
                title="Directions"
              >
                <DirectionsIcon size={20} />
              </button>
            </div>

            <div className={styles.chipsBar} role="group" aria-label="Map layers">
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
              <button
                type="button"
                className={styles.chip}
                aria-pressed={showPlayers}
                onClick={() => {
                  setShowPlayers((s) => !s);
                  setPlayerListOpen(false);
                }}
                title="Live player positions from the server"
              >
                <PeopleIcon size={14} />
                Players
                {showPlayers && <span className={styles.chipCount}>{livePlayers.length}</span>}
              </button>
            </div>
          </>
        )}

        {panelHasContent && (
          <section
            id="map-sheet"
            className={`${styles.panel} ${sheetOpen ? "" : styles.sheetClosed}`}
            aria-label="Map panel"
          >
            <button
              type="button"
              className={styles.sheetHandle}
              onClick={() => setSheetOpen((o) => !o)}
              aria-expanded={sheetOpen}
              aria-label={sheetOpen ? "Collapse panel" : "Expand panel"}
            >
              <span />
            </button>

            <div className={styles.panelBody}>
              <div aria-live="polite">
                {authMessage && (
                  <p className={styles.warn} role="alert">
                    {authMessage}{" "}
                    <button type="button" className={styles.textButton} onClick={() => setAuthMessage(null)} aria-label="Dismiss">
                      ×
                    </button>
                  </p>
                )}
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

              {playerListOpen && (
                <section className={styles.playerPanel} aria-label="Players online">
                  <div className={styles.sectionHead}>
                    <h2>
                      Players online
                      <span className={styles.badge}>
                        {livePlayers.length}
                        {players.max > 0 ? ` / ${players.max}` : ""}
                      </span>
                    </h2>
                    <button type="button" className={styles.iconButton} onClick={() => setPlayerListOpen(false)} aria-label="Close">
                      <CloseIcon size={18} />
                    </button>
                  </div>
                  {!showPlayers ? (
                    <p className={styles.muted}>The player layer is switched off.</p>
                  ) : livePlayers.length === 0 ? (
                    <p className={styles.muted}>Nobody is in {worldLabel} right now.</p>
                  ) : (
                    <ul className={styles.results}>
                      {livePlayers.map((p) => (
                        <li key={p.uuid}>
                          <button type="button" className={styles.result} onClick={() => openPlayerOnMap(p.uuid)}>
                            <span className={styles.resultDot} style={{ background: playerColour(p.uuid) }} aria-hidden="true" />
                            <span className={styles.resultText}>
                              <span className={styles.resultName}>{p.name}</span>
                              <span className={styles.resultSub}>{playerTitle(p).split(" · ")[1]}</span>
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}

              {mode === "directions" ? (
                <DirectionsResults
                  view={routeView}
                  allFeatures={allFeatures}
                  highlightLeg={hoverLeg}
                  pickTarget={pickTarget}
                  onHoverLeg={setHoverLeg}
                  onFocusLeg={(i) => {
                    const leg = shownRoute?.legs[i];
                    if (leg) setFitRequest((prev) => ({ points: leg.points, nonce: (prev?.nonce ?? 0) + 1 }));
                  }}
                  onRetry={() => setRouteRetry((n) => n + 1)}
                />
              ) : editMode ? (
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
                  onDirections={directionsTo}
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
              ) : null}
            </div>
          </section>
        )}
      </div>

      {/* Click-away for the two popovers. */}
      {(accountOpen || worldPickerOpen) && (
        <button
          type="button"
          className={styles.scrim}
          aria-label="Close menu"
          onClick={() => {
            setAccountOpen(false);
            setWorldPickerOpen(false);
          }}
        />
      )}

      {/* Account, top right, as on Google Maps. */}
      <div className={styles.topRight}>
        <button
          type="button"
          className={styles.avatarButton}
          aria-haspopup="dialog"
          aria-expanded={accountOpen}
          onClick={() => setAccountOpen((o) => !o)}
          title={auth?.loggedIn ? auth.name : "Account"}
          aria-label={auth?.loggedIn ? `Account: ${auth.name}` : "Account"}
        >
          {auth?.loggedIn ? (
            <span className={styles.avatarInitial} style={{ background: playerColour(auth.uuid) }}>
              {auth.name.slice(0, 1).toUpperCase()}
            </span>
          ) : (
            <PersonIcon size={22} />
          )}
        </button>
        {accountOpen && (
          <div className={styles.popover} role="dialog" aria-label="Account">
            {auth?.loggedIn ? (
              <>
                <p className={styles.popoverName} title={auth.uuid}>
                  {auth.name}
                </p>
                <p className={styles.muted}>{canEdit ? "Can edit the map" : "View only"}</p>
                <button
                  type="button"
                  className={styles.secondaryButton}
                  onClick={() => {
                    setAccountOpen(false);
                    logout();
                  }}
                >
                  Log out
                </button>
              </>
            ) : (
              <>
                <p className={styles.popoverName}>Not signed in</p>
                <p className={styles.muted}>
                  Run <code>/mapedit</code> in game to get a login link and edit the map.
                </p>
              </>
            )}
            {USE_FIXTURES && <p className={styles.footnote}>Fixture data (NEXT_PUBLIC_USE_FIXTURES=1) · no tiles</p>}
          </div>
        )}
      </div>

      {/* World picker, bottom left, as Google Maps' basemap card. */}
      <div className={styles.bottomLeft}>
        <button
          type="button"
          className={styles.worldCard}
          aria-haspopup="listbox"
          aria-expanded={worldPickerOpen}
          onClick={() => setWorldPickerOpen((o) => !o)}
          disabled={worldList.length === 0}
        >
          <span className={styles.worldCardIcon} aria-hidden="true">
            <LayersIcon size={22} />
          </span>
          <span className={styles.worldCardLabel}>{worldLabel}</span>
        </button>
        {worldPickerOpen && worldList.length > 0 && (
          <ul className={styles.worldMenu} role="listbox" aria-label="World">
            {worldList.map((w) => (
              <li key={w.id} role="option" aria-selected={w.id === worldId}>
                <button
                  type="button"
                  className={styles.worldOption}
                  onClick={() => {
                    setWorldPickerOpen(false);
                    changeWorld(w.id);
                  }}
                >
                  {w.name && w.name !== w.id ? `${w.name} (${w.id})` : w.id}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Round map controls, bottom right, above Leaflet's zoom buttons. */}
      <div className={styles.bottomRight}>
        {canEdit && (
          <button
            type="button"
            className={styles.fab}
            aria-pressed={editMode}
            onClick={() => switchMode(editMode ? "explore" : "edit")}
            title={editMode ? "Leave the editor" : "Edit the map"}
            aria-label={editMode ? "Leave the editor" : "Edit the map"}
          >
            <PencilIcon size={20} />
          </button>
        )}
        <button
          type="button"
          className={styles.fab}
          aria-pressed={playerListOpen}
          onClick={() => {
            setPlayerListOpen((o) => !o);
            setSheetOpen(true);
          }}
          title="Players online"
          aria-label="Players online"
        >
          <PeopleIcon size={20} />
          {showPlayers && livePlayers.length > 0 && <span className={styles.fabBadge}>{livePlayers.length}</span>}
        </button>
      </div>
    </div>
  );
}
