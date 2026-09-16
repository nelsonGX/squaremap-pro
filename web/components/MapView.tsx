"use client";

/**
 * Leaflet map configured like squaremap v1.3.12's frontend (CRS.Simple, 512px tiles,
 * `tiles/<world>/{z}/{x}_{y}.png`, maxNativeZoom = zoom.max, min zoom 0, max zoom = max + extra),
 * with the drawn features on top and the editor's draft layer (leaflet-geoman) in edit mode.
 * Client-only: loaded through `next/dynamic` with `ssr: false` because Leaflet and leaflet-geoman
 * touch `window` (geoman extends the global `L` that Leaflet's UMD build installs on import).
 */
import { useEffect, useRef } from "react";
import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import "@geoman-io/leaflet-geoman-free";
import "@geoman-io/leaflet-geoman-free/dist/leaflet-geoman.css";
import type { Feature, FeatureType, OnlinePlayer, RouteResponse, XZ } from "../lib/api/types";
import { SNAP_DISTANCE_PX, blocksPerPixel } from "../lib/editor/geometry";
import { blockBounds, boundsToLatLngs, flyTarget, vertexLatLngs } from "../lib/features/geometry";
import { legLineStyle } from "../lib/navigation/legs";
import { playerColour, playerTitle, screenHeading } from "../lib/players/players";
import { displayName, drawOrder, featureStyle, railwayColourMap, type FeatureStyle } from "../lib/features/styles";
import { SQUAREMAP_TILE_SIZE, toBlock, toLatLng, toPoint, type LatLngLike } from "../lib/squaremapCrs";
import type { WorldSettingsZoom } from "../lib/squaremapSettings";
import styles from "./MapView.module.css";

// Only the map and layers created with `pmIgnore: false` get geoman handlers (features stay plain
// Leaflet layers). geoman lives on the global `L` (Leaflet's UMD build sets `window.L`), which may be a
// different object than this module's namespace import.
(globalThis as unknown as { L: typeof L }).L.PM.setOptIn(true);

/** 1x1 transparent PNG, standing in for squaremap's `errorTileUrl: "images/clear.png"`. */
const CLEAR_PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

/** Must match the panel breakpoint in ViewerApp.module.css. */
const SHEET_BREAKPOINT_PX = 700;
const HALO_COLOUR = "#1a73e8";
const DRAW_COLOUR = "#1a73e8";

export interface FlyRequest {
  featureId: string;
  /** Changes on every request so flying to the same feature twice works. */
  nonce: number;
}

export interface MapDraft {
  /** Changes when a new draft starts (layers are rebuilt). */
  key: number;
  type: FeatureType;
  featureId: string | null;
  geometry: XZ[];
  style: FeatureStyle;
}

export interface MapEditing {
  draft: MapDraft | null;
  /** Building/road/railway: drawn or edited vertex positions (fractional blocks) + current blocks-per-pixel. */
  onGeometry: (positions: { x: number; z: number }[], blocksPerPixel: number) => void;
  /** Station draft: a click anywhere on the map (fractional blocks). */
  onPlace: (position: { x: number; z: number }, blocksPerPixel: number) => void;
}

export interface MapNavigation {
  from: XZ | null;
  to: XZ | null;
  /** Latest ok route to draw (null while none). */
  route: RouteResponse | null;
  highlightLeg: number | null;
  /** Map click (anywhere, features included) -> block. */
  onClick: (block: XZ) => void;
  /** Endpoint marker dragged to a block. */
  onDrag: (which: "from" | "to", block: XZ) => void;
  onHoverLeg: (index: number | null) => void;
}

export interface MapViewProps {
  /** Tile URL template, or null while no world is selected. */
  tileTemplate: string | null;
  /** Changes whenever the world (and therefore the view) should be reset. */
  worldKey: string;
  zoom: WorldSettingsZoom;
  spawn: { x: number; z: number };
  /** Features to draw (already filtered by layer visibility, without the feature being edited). */
  features: readonly Feature[];
  /** All features of the world (station colours come from railways even when railways are hidden). */
  allFeatures: readonly Feature[];
  selectedId: string | null;
  flyRequest: FlyRequest | null;
  /** Feature click → id; click on empty map → null. */
  onSelect: (featureId: string | null) => void;
  /** Non-null in edit mode. */
  editing: MapEditing | null;
  /** Non-null in directions mode. */
  navigation: MapNavigation | null;
  /** Fit the view to these points when `nonce` changes (route legs, a player, …). */
  fitRequest: { points: XZ[]; nonce: number } | null;
  /** Live players of the current world; empty when the layer is off. */
  players: readonly OnlinePlayer[];
  /** Player marker click → uuid. */
  onPlayerClick?: (uuid: string) => void;
}

const ll = (p: LatLngLike) => L.latLng(p.lat, p.lng);

/**
 * fitBounds options keeping the target clear of the side panel / bottom sheet.
 *
 * Views are moved with `fitBounds` (animated pan or zoom), never `flyTo`/`flyToBounds`: Leaflet 1.9.4's
 * fly animation moves the pixel origin in `_move` but fires `zoom` only when the zoom changed since
 * the previous frame. When the fly ends at the start zoom and its first frame is already the last
 * (slow or throttled frames, headless virtual time), markers never get `zoom`/`viewreset` and stay at
 * stale layer points while canvas renderers redraw on `moveend`, offsetting them by the pan distance.
 */
function fitOptions(map: L.Map, maxZoom: number): L.FitBoundsOptions {
  const size = map.getSize();
  const sheet = size.x < SHEET_BREAKPOINT_PX;
  return {
    paddingTopLeft: sheet ? L.point(32, 32) : L.point(Math.min(420, size.x / 2), 48),
    paddingBottomRight: sheet ? L.point(32, Math.round(size.y * 0.5)) : L.point(48, 48),
    maxZoom,
    animate: true,
    duration: 0.5,
  };
}

/**
 * Marker for one online player: a coloured dot with a heading cone and a name label, in the style of
 * a Google Maps live marker. The whole icon is re-created only when the name or colour changes;
 * moves are applied with `setLatLng` and the heading with a CSS variable, so the CSS transition
 * animates between polls.
 */
function playerIcon(p: OnlinePlayer): L.DivIcon {
  const colour = playerColour(p.uuid);
  return L.divIcon({
    className: styles.playerMarker,
    html:
      `<div class="${styles.player}" style="--player-colour:${colour}">` +
      `<span class="${styles.playerCone}" aria-hidden="true"></span>` +
      `<span class="${styles.playerDot}" aria-hidden="true"></span>` +
      `<span class="${styles.playerName}">${escapeHtml(p.name)}</span>` +
      `</div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  });
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    c === "&" ? "&amp;" : c === "<" ? "&lt;" : c === ">" ? "&gt;" : c === '"' ? "&quot;" : "&#39;",
  );
}

function endpointIcon(which: "from" | "to"): L.DivIcon {
  return L.divIcon({
    className: "",
    html: `<div class="${styles.endpoint} ${which === "from" ? styles.endpointFrom : styles.endpointTo}">${which === "from" ? "A" : "B"}</div>`,
    iconSize: [26, 26],
    iconAnchor: [13, 13],
  });
}

/** First ring / line of a geoman-drawn or edited path as LatLngs. */
function pathLatLngs(layer: L.Layer): L.LatLng[] {
  if (!(layer instanceof L.Polyline)) return [];
  const raw = layer.getLatLngs() as unknown;
  let v: unknown = raw;
  while (Array.isArray(v) && Array.isArray(v[0])) v = v[0];
  return Array.isArray(v) ? (v as L.LatLng[]) : [];
}

export default function MapView(props: MapViewProps) {
  const { tileTemplate, worldKey, zoom, spawn, features, allFeatures, selectedId, flyRequest, onSelect, editing, navigation, fitRequest, players, onPlayerClick } = props;
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const featureLayerRef = useRef<L.LayerGroup | null>(null);
  const haloLayerRef = useRef<L.LayerGroup | null>(null);
  const haloRendererRef = useRef<L.Renderer | null>(null);
  const draftLayerRef = useRef<L.LayerGroup | null>(null);
  const draftRendererRef = useRef<L.Renderer | null>(null);
  const draftPathRef = useRef<{ key: number; layer: L.Polyline } | null>(null);
  const maxZoomRef = useRef(zoom.max);
  const selectRef = useRef(onSelect);
  const editingRef = useRef(editing);
  const navigationRef = useRef(navigation);
  const routeLayerRef = useRef<L.LayerGroup | null>(null);
  const routeHighlightRef = useRef<L.LayerGroup | null>(null);
  const routeRendererRef = useRef<L.Renderer | null>(null);
  const endpointLayerRef = useRef<L.LayerGroup | null>(null);
  const playerLayerRef = useRef<L.LayerGroup | null>(null);
  /** uuid -> marker, so a moving player keeps its DOM element (and its CSS transition). */
  const playerMarkersRef = useRef(new Map<string, { marker: L.Marker; name: string }>());
  const playerClickRef = useRef(onPlayerClick);
  /** The DOM event of the last feature click, so the map click it bubbles into is not "empty map". */
  const featureClickEventRef = useRef<Event | null>(null);

  useEffect(() => {
    selectRef.current = onSelect;
    playerClickRef.current = onPlayerClick;
    editingRef.current = editing;
    navigationRef.current = navigation;
    maxZoomRef.current = zoom.max;
  }, [onSelect, onPlayerClick, editing, navigation, zoom.max]);

  // Create the map once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const map = L.map(el, {
      crs: L.CRS.Simple,
      center: [0, 0],
      zoom: 0,
      attributionControl: false,
      zoomControl: false,
      preferCanvas: true,
      pmIgnore: false,
    });
    L.control.zoom({ position: "bottomright" }).addTo(map);
    // Selection halo sits below the features (overlayPane z-index 400); the draft above them.
    map.createPane("halo").style.zIndex = "390";
    map.createPane("draft").style.zIndex = "450";
    map.createPane("route").style.zIndex = "420";
    // Players ride above every drawn layer but below Leaflet's own popups/controls.
    map.createPane("players").style.zIndex = "620";
    routeRendererRef.current = L.canvas({ pane: "route" });
    haloRendererRef.current = L.canvas({ pane: "halo" });
    draftRendererRef.current = L.svg({ pane: "draft" });
    map.on("click", (e: L.LeafletMouseEvent) => {
      const fromFeature = featureClickEventRef.current === e.originalEvent;
      featureClickEventRef.current = null;
      if (map.pm.globalDrawModeEnabled()) return;
      const nav = navigationRef.current;
      if (nav) {
        nav.onClick(toBlock(e.latlng, maxZoomRef.current));
        return;
      }
      const ed = editingRef.current;
      if (ed?.draft?.type === "station") {
        ed.onPlace(toPoint(e.latlng, maxZoomRef.current), blocksPerPixel(map.getZoom(), maxZoomRef.current));
        return;
      }
      if (!fromFeature) selectRef.current(null);
    });
    map.on("pm:create", (e) => {
      const positions = pathLatLngs(e.layer).map((p) => toPoint(p, maxZoomRef.current));
      e.layer.remove();
      editingRef.current?.onGeometry(positions, blocksPerPixel(map.getZoom(), maxZoomRef.current));
    });
    haloLayerRef.current = L.layerGroup().addTo(map);
    featureLayerRef.current = L.layerGroup().addTo(map);
    draftLayerRef.current = L.layerGroup().addTo(map);
    routeHighlightRef.current = L.layerGroup().addTo(map);
    routeLayerRef.current = L.layerGroup().addTo(map);
    endpointLayerRef.current = L.layerGroup().addTo(map);
    playerLayerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;
    const ro = new ResizeObserver(() => map.invalidateSize());
    ro.observe(el);
    const playerMarkers = playerMarkersRef.current;
    return () => {
      ro.disconnect();
      map.pm.disableDraw();
      map.remove();
      mapRef.current = null;
      tileRef.current = null;
      draftPathRef.current = null;
      playerMarkers.clear();
    };
  }, []);

  // World: tile layer + view, as squaremap World.load / LayerControl.createTileLayer.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (tileRef.current) {
      map.removeLayer(tileRef.current);
      tileRef.current = null;
    }
    if (tileTemplate) {
      tileRef.current = L.tileLayer(tileTemplate, {
        tileSize: SQUAREMAP_TILE_SIZE,
        minNativeZoom: 0,
        maxNativeZoom: zoom.max,
        errorTileUrl: CLEAR_PNG,
      }).addTo(map);
      tileRef.current.bringToBack();
    }
    map
      .setMinZoom(0)
      .setMaxZoom(zoom.max + zoom.extra)
      .setView(ll(toLatLng(spawn.x, spawn.z, zoom.max)), zoom.def);
    // worldKey intentionally drives the reset; spawn/zoom values are part of it.
  }, [tileTemplate, worldKey, zoom.max, zoom.def, zoom.extra, spawn.x, spawn.z]);

  // Features. Road and railway lines are geoman snap targets (vertex snapping while drawing/editing).
  useEffect(() => {
    const group = featureLayerRef.current;
    if (!group) return;
    group.clearLayers();
    const colours = railwayColourMap(allFeatures);
    for (const f of drawOrder(features)) {
      const style = featureStyle(f, colours);
      const latlngs = vertexLatLngs(f.geometry, zoom.max).map(ll);
      const first = latlngs[0];
      if (!first) continue;
      const snapIgnore = !(f.type === "road" || f.type === "railway");
      const common = { snapIgnore } as const;
      let main: L.Path;
      switch (style.shape) {
        case "polygon":
          main = L.polygon(latlngs, { ...style.main, ...common });
          break;
        case "line":
          if (style.casing) L.polyline(latlngs, { ...style.casing, interactive: false, snapIgnore: true }).addTo(group);
          main = L.polyline(latlngs, { ...style.main, ...common });
          break;
        case "circle":
          main = L.circleMarker(first, { ...style.main, ...common });
          break;
      }
      main
        .bindTooltip(displayName(f), { sticky: style.shape !== "circle", direction: "top", offset: [0, -6] })
        .on("click", (e: L.LeafletMouseEvent) => {
          // The event bubbles to the map (so geoman can draw on top of features); mark it.
          featureClickEventRef.current = e.originalEvent;
          const map = mapRef.current;
          if (map?.pm.globalDrawModeEnabled()) return;
          if (editingRef.current?.draft?.type === "station") return; // map click handler places it
          if (navigationRef.current) return; // map click handler picks the point
          selectRef.current(f.id);
        })
        .addTo(group);
    }
  }, [features, allFeatures, zoom.max]);

  // Selection halo.
  useEffect(() => {
    const group = haloLayerRef.current;
    const renderer = haloRendererRef.current;
    if (!group || !renderer) return;
    group.clearLayers();
    const f = selectedId ? features.find((x) => x.id === selectedId) : undefined;
    if (!f) return;
    const latlngs = vertexLatLngs(f.geometry, zoom.max).map(ll);
    const first = latlngs[0];
    if (!first) return;
    const halo = {
      renderer,
      color: HALO_COLOUR,
      opacity: 0.45,
      interactive: false,
      snapIgnore: true,
      lineCap: "round",
      lineJoin: "round",
    } as const;
    switch (f.type) {
      case "building":
        L.polygon(latlngs, { ...halo, weight: 8, fill: false }).addTo(group);
        break;
      case "road":
      case "railway":
        L.polyline(latlngs, { ...halo, weight: 18 }).addTo(group);
        break;
      case "station":
        L.circleMarker(first, { ...halo, radius: 13, weight: 0, fill: true, fillColor: HALO_COLOUR, fillOpacity: 0.35 }).addTo(group);
        break;
    }
  }, [selectedId, features, zoom.max]);

  const draft = editing?.draft ?? null;
  const draftKey = draft?.key ?? null;
  const draftType = draft?.type ?? null;
  const draftEmpty = !draft || draft.geometry.length === 0;

  // Draw mode: a new building/road/railway draft without geometry is drawn with geoman.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    const shape = draftType === "building" ? "Polygon" : draftType === "road" || draftType === "railway" ? "Line" : null;
    if (draftKey === null || !shape || !draftEmpty) {
      map.pm.disableDraw();
      return;
    }
    map.pm.enableDraw(shape, {
      snappable: shape === "Line",
      snapDistance: SNAP_DISTANCE_PX,
      snapSegment: false,
      snapMiddle: false,
      finishOn: "dblclick",
      finishOnEnter: true,
      allowSelfIntersection: true,
      templineStyle: { color: DRAW_COLOUR, weight: 3 },
      hintlineStyle: { color: DRAW_COLOUR, weight: 2, dashArray: [5, 5] },
      pathOptions: { color: DRAW_COLOUR },
      tooltips: false,
    });
    return () => {
      map.pm.disableDraw();
    };
  }, [draftKey, draftType, draftEmpty]);

  // Draft layer: editable path (drag vertices, drag middle handles to add, right-click to remove) or station marker.
  useEffect(() => {
    const map = mapRef.current;
    const group = draftLayerRef.current;
    const renderer = draftRendererRef.current;
    if (!map || !group || !renderer) return;
    const d = draft;
    if (!d || d.geometry.length === 0) {
      group.clearLayers();
      draftPathRef.current = null;
      return;
    }
    const latlngs = vertexLatLngs(d.geometry, zoom.max).map(ll);
    if (d.type === "station") {
      group.clearLayers();
      draftPathRef.current = null;
      const style = d.style.shape === "circle" ? d.style.main : { radius: 6, color: DRAW_COLOUR, weight: 3, opacity: 1, fillColor: "#fff", fillOpacity: 1 };
      L.circleMarker(latlngs[0]!, { ...style, radius: style.radius + 2, renderer, interactive: false, snapIgnore: true }).addTo(group);
      return;
    }
    const existing = draftPathRef.current;
    const editOptions: L.PM.EditModeOptions = {
      snappable: d.type !== "building",
      snapDistance: SNAP_DISTANCE_PX,
      snapSegment: false,
      snapMiddle: false,
      allowSelfIntersection: true,
      draggable: false,
      removeLayerBelowMinVertexCount: false,
    };
    const base = d.style.shape === "circle" ? { color: DRAW_COLOUR, weight: 4, opacity: 1 } : d.style.main;
    if (existing && existing.key === d.key) {
      // Same draft: follow style edits; re-sync points only when the stored (snapped, block-centred)
      // geometry differs from the layer.
      existing.layer.setStyle(base);
      const current = pathLatLngs(existing.layer);
      const inSync =
        current.length === latlngs.length && current.every((p, i) => p.lat === latlngs[i]!.lat && p.lng === latlngs[i]!.lng);
      if (inSync) return;
      existing.layer.setLatLngs(latlngs);
      existing.layer.pm.disable();
      existing.layer.pm.enable(editOptions);
      return;
    }
    group.clearLayers();
    const pathOptions: L.PolylineOptions = { ...base, renderer, pmIgnore: false, snapIgnore: true, bubblingMouseEvents: false };
    // L.Polygon extends L.Polyline at runtime; the typings diverge only in toGeoJSON.
    const layer: L.Polyline =
      d.type === "building" ? (L.polygon(latlngs, pathOptions) as unknown as L.Polyline) : L.polyline(latlngs, pathOptions);
    layer.addTo(group);
    layer.pm.enable(editOptions);
    layer.on("pm:edit", () => {
      const positions = pathLatLngs(layer).map((p) => toPoint(p, maxZoomRef.current));
      editingRef.current?.onGeometry(positions, blocksPerPixel(map.getZoom(), maxZoomRef.current));
    });
    draftPathRef.current = { key: d.key, layer };
  }, [draft, zoom.max]);

  // Leaving edit mode: clear the draft layer and any draw mode.
  const editingActive = editing !== null;
  useEffect(() => {
    if (editingActive) return;
    mapRef.current?.pm.disableDraw();
    draftLayerRef.current?.clearLayers();
    draftPathRef.current = null;
  }, [editingActive]);

  // Fly to a feature, keeping it clear of the side panel / bottom sheet.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !flyRequest) return;
    const f = allFeatures.find((x) => x.id === flyRequest.featureId);
    const target = f ? flyTarget(f, zoom.max) : null;
    if (!target) return;
    const bounds =
      target.kind === "point"
        ? L.latLngBounds(ll(target.latLng), ll(target.latLng))
        : L.latLngBounds(ll(target.bounds[0]), ll(target.bounds[1]));
    map.fitBounds(bounds, fitOptions(map, zoom.max));
    // Only a new request should fly; feature list refreshes must not move the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flyRequest]);

  const route = navigation?.route ?? null;
  const highlightLeg = navigation?.highlightLeg ?? null;
  const navFrom = navigation?.from ?? null;
  const navTo = navigation?.to ?? null;

  // Route legs, styled per mode; hovering a leg reports it.
  useEffect(() => {
    const group = routeLayerRef.current;
    const renderer = routeRendererRef.current;
    if (!group || !renderer) return;
    group.clearLayers();
    if (!route) return;
    route.legs.forEach((leg, index) => {
      const latlngs = vertexLatLngs(leg.points, zoom.max).map(ll);
      const style = legLineStyle(leg, allFeatures);
      const common = { renderer, snapIgnore: true, lineCap: "round", lineJoin: "round" } as const;
      if (style.casing) {
        L.polyline(latlngs, { ...common, color: style.casing.color, weight: style.casing.weight, opacity: 0.9, interactive: false }).addTo(group);
      }
      L.polyline(latlngs, { ...common, color: style.color, weight: style.weight, opacity: style.opacity, dashArray: style.dashArray })
        .on("mouseover", () => navigationRef.current?.onHoverLeg(index))
        .on("mouseout", () => navigationRef.current?.onHoverLeg(null))
        .addTo(group);
    });
  }, [route, allFeatures, zoom.max]);

  // Highlighted leg (under the route lines).
  useEffect(() => {
    const group = routeHighlightRef.current;
    const renderer = routeRendererRef.current;
    if (!group || !renderer) return;
    group.clearLayers();
    const leg = highlightLeg !== null ? route?.legs[highlightLeg] : undefined;
    if (!leg) return;
    L.polyline(vertexLatLngs(leg.points, zoom.max).map(ll), {
      renderer,
      color: "#fbbc04",
      weight: 18,
      opacity: 0.55,
      lineCap: "round",
      lineJoin: "round",
      interactive: false,
      snapIgnore: true,
    }).addTo(group);
  }, [route, highlightLeg, zoom.max]);

  // Draggable A / B markers.
  useEffect(() => {
    const group = endpointLayerRef.current;
    if (!group) return;
    group.clearLayers();
    const add = (which: "from" | "to", p: XZ | null) => {
      if (!p) return;
      const marker = L.marker(ll(vertexLatLngs([p], zoom.max)[0]!), {
        icon: endpointIcon(which),
        draggable: true,
        keyboard: false,
        title: which === "from" ? `Start ${p.x}, ${p.z}` : `Destination ${p.x}, ${p.z}`,
        zIndexOffset: 1000,
        snapIgnore: true,
      });
      marker.on("dragend", () => navigationRef.current?.onDrag(which, toBlock(marker.getLatLng(), maxZoomRef.current)));
      marker.addTo(group);
    };
    add("from", navFrom);
    add("to", navTo);
  }, [navFrom, navTo, zoom.max]);

  // Live players: markers are reused across polls so CSS transitions animate the movement.
  useEffect(() => {
    const group = playerLayerRef.current;
    if (!group) return;
    const markers = playerMarkersRef.current;
    const seen = new Set<string>();
    for (const p of players) {
      seen.add(p.uuid);
      const latlng = ll(vertexLatLngs([{ x: p.x, z: p.z }], zoom.max)[0]!);
      const existing = markers.get(p.uuid);
      let marker: L.Marker;
      if (existing && existing.name === p.name) {
        marker = existing.marker;
        marker.setLatLng(latlng);
      } else {
        existing?.marker.remove();
        marker = L.marker(latlng, {
          icon: playerIcon(p),
          pane: "players",
          keyboard: false,
          interactive: true,
          snapIgnore: true,
          zIndexOffset: 500,
        });
        marker.on("click", (e: L.LeafletMouseEvent) => {
          // Do not let the click reach the map (which would drop the selection or pick a route point).
          L.DomEvent.stopPropagation(e);
          playerClickRef.current?.(p.uuid);
        });
        marker.addTo(group);
        markers.set(p.uuid, { marker, name: p.name });
      }
      const el = marker.getElement();
      if (el) {
        el.style.setProperty("--player-heading", `${screenHeading(p.yaw)}deg`);
        el.title = playerTitle(p);
      }
    }
    for (const [uuid, entry] of markers) {
      if (!seen.has(uuid)) {
        entry.marker.remove();
        markers.delete(uuid);
      }
    }
  }, [players, zoom.max]);

  // Fit to a route or leg on request.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !fitRequest) return;
    const b = blockBounds(fitRequest.points);
    if (!b) return;
    const [sw, ne] = boundsToLatLngs(b, zoom.max);
    map.fitBounds(L.latLngBounds(ll(sw), ll(ne)), fitOptions(map, zoom.max));
    // Only a new request should move the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitRequest]);

  return (
    <div
      ref={containerRef}
      className={`${styles.map} ${draft?.type === "station" ? styles.placing : ""}`}
      role="application"
      aria-label="Map"
    />
  );
}
