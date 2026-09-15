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
import type { Feature, FeatureType, XZ } from "../lib/api/types";
import { SNAP_DISTANCE_PX, blocksPerPixel } from "../lib/editor/geometry";
import { flyTarget, vertexLatLngs } from "../lib/features/geometry";
import { displayName, drawOrder, featureStyle, railwayColourMap, type FeatureStyle } from "../lib/features/styles";
import { SQUAREMAP_TILE_SIZE, toLatLng, toPoint, type LatLngLike } from "../lib/squaremapCrs";
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
}

const ll = (p: LatLngLike) => L.latLng(p.lat, p.lng);

/** First ring / line of a geoman-drawn or edited path as LatLngs. */
function pathLatLngs(layer: L.Layer): L.LatLng[] {
  if (!(layer instanceof L.Polyline)) return [];
  const raw = layer.getLatLngs() as unknown;
  let v: unknown = raw;
  while (Array.isArray(v) && Array.isArray(v[0])) v = v[0];
  return Array.isArray(v) ? (v as L.LatLng[]) : [];
}

export default function MapView(props: MapViewProps) {
  const { tileTemplate, worldKey, zoom, spawn, features, allFeatures, selectedId, flyRequest, onSelect, editing } = props;
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
  /** The DOM event of the last feature click, so the map click it bubbles into is not "empty map". */
  const featureClickEventRef = useRef<Event | null>(null);

  useEffect(() => {
    selectRef.current = onSelect;
    editingRef.current = editing;
    maxZoomRef.current = zoom.max;
  }, [onSelect, editing, zoom.max]);

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
    haloRendererRef.current = L.canvas({ pane: "halo" });
    draftRendererRef.current = L.svg({ pane: "draft" });
    map.on("click", (e: L.LeafletMouseEvent) => {
      const fromFeature = featureClickEventRef.current === e.originalEvent;
      featureClickEventRef.current = null;
      if (map.pm.globalDrawModeEnabled()) return;
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
    mapRef.current = map;
    const ro = new ResizeObserver(() => map.invalidateSize());
    ro.observe(el);
    return () => {
      ro.disconnect();
      map.pm.disableDraw();
      map.remove();
      mapRef.current = null;
      tileRef.current = null;
      draftPathRef.current = null;
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
    const size = map.getSize();
    const sheet = size.x < SHEET_BREAKPOINT_PX;
    const options: L.FitBoundsOptions = {
      paddingTopLeft: sheet ? L.point(32, 32) : L.point(Math.min(420, size.x / 2), 48),
      paddingBottomRight: sheet ? L.point(32, Math.round(size.y * 0.5)) : L.point(48, 48),
      maxZoom: zoom.max,
      duration: 0.8,
    };
    const bounds =
      target.kind === "point"
        ? L.latLngBounds(ll(target.latLng), ll(target.latLng))
        : L.latLngBounds(ll(target.bounds[0]), ll(target.bounds[1]));
    map.flyToBounds(bounds, options);
    // Only a new request should fly; feature list refreshes must not move the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flyRequest]);

  return (
    <div
      ref={containerRef}
      className={`${styles.map} ${draft?.type === "station" ? styles.placing : ""}`}
      role="application"
      aria-label="Map"
    />
  );
}
