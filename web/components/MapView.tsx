"use client";

/**
 * Leaflet map configured like squaremap v1.3.12's frontend (CRS.Simple, 512px tiles,
 * `tiles/<world>/{z}/{x}_{y}.png`, maxNativeZoom = zoom.max, min zoom 0, max zoom = max + extra),
 * with the drawn features on top. Client-only: loaded through `next/dynamic` with `ssr: false`
 * because Leaflet touches `window`.
 */
import { useEffect, useRef } from "react";
import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import type { Feature } from "../lib/api/types";
import { flyTarget, vertexLatLngs } from "../lib/features/geometry";
import { displayName, drawOrder, featureStyle, railwayColourMap } from "../lib/features/styles";
import { SQUAREMAP_TILE_SIZE, toLatLng, type LatLngLike } from "../lib/squaremapCrs";
import type { WorldSettingsZoom } from "../lib/squaremapSettings";
import styles from "./MapView.module.css";

/** 1x1 transparent PNG, standing in for squaremap's `errorTileUrl: "images/clear.png"`. */
const CLEAR_PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

/** Must match the panel breakpoint in ViewerApp.module.css. */
const SHEET_BREAKPOINT_PX = 700;
const HALO_COLOUR = "#1a73e8";

export interface FlyRequest {
  featureId: string;
  /** Changes on every request so flying to the same feature twice works. */
  nonce: number;
}

export interface MapViewProps {
  /** Tile URL template, or null while no world is selected. */
  tileTemplate: string | null;
  /** Changes whenever the world (and therefore the view) should be reset. */
  worldKey: string;
  zoom: WorldSettingsZoom;
  spawn: { x: number; z: number };
  /** Features to draw (already filtered by layer visibility). */
  features: readonly Feature[];
  /** All features of the world (station colours come from railways even when railways are hidden). */
  allFeatures: readonly Feature[];
  selectedId: string | null;
  flyRequest: FlyRequest | null;
  onSelect: (featureId: string | null) => void;
}

const ll = (p: LatLngLike) => L.latLng(p.lat, p.lng);

export default function MapView(props: MapViewProps) {
  const { tileTemplate, worldKey, zoom, spawn, features, allFeatures, selectedId, flyRequest, onSelect } = props;
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const featureLayerRef = useRef<L.LayerGroup | null>(null);
  const haloLayerRef = useRef<L.LayerGroup | null>(null);
  const haloRendererRef = useRef<L.Renderer | null>(null);
  const selectRef = useRef(onSelect);

  useEffect(() => {
    selectRef.current = onSelect;
  }, [onSelect]);

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
    });
    L.control.zoom({ position: "bottomright" }).addTo(map);
    // Selection halo sits below the features (overlayPane z-index 400).
    map.createPane("halo").style.zIndex = "390";
    haloRendererRef.current = L.canvas({ pane: "halo" });
    // Clicks on features do not bubble (bubblingMouseEvents: false), so this is a click on empty map.
    map.on("click", () => selectRef.current(null));
    haloLayerRef.current = L.layerGroup().addTo(map);
    featureLayerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;
    const ro = new ResizeObserver(() => map.invalidateSize());
    ro.observe(el);
    return () => {
      ro.disconnect();
      map.remove();
      mapRef.current = null;
      tileRef.current = null;
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

  // Features.
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
      const common = { bubblingMouseEvents: false } as const;
      let main: L.Path;
      switch (style.shape) {
        case "polygon":
          main = L.polygon(latlngs, { ...style.main, ...common });
          break;
        case "line":
          if (style.casing) L.polyline(latlngs, { ...style.casing, interactive: false }).addTo(group);
          main = L.polyline(latlngs, { ...style.main, ...common });
          break;
        case "circle":
          main = L.circleMarker(first, { ...style.main, ...common });
          break;
      }
      main
        .bindTooltip(displayName(f), { sticky: style.shape !== "circle", direction: "top", offset: [0, -6] })
        .on("click", () => selectRef.current(f.id))
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
    const halo = { renderer, color: HALO_COLOUR, opacity: 0.45, interactive: false, lineCap: "round", lineJoin: "round" } as const;
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
      target.kind === "point" ? L.latLngBounds(ll(target.latLng), ll(target.latLng)) : L.latLngBounds(ll(target.bounds[0]), ll(target.bounds[1]));
    map.flyToBounds(bounds, options);
    // Only a new request should fly; feature list refreshes must not move the map.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flyRequest]);

  return <div ref={containerRef} className={styles.map} role="application" aria-label="Map" />;
}
