"use client";

/**
 * Leaflet map configured like squaremap v1.3.12's frontend (CRS.Simple, 512px tiles,
 * `tiles/<world>/{z}/{x}_{y}.png`, maxNativeZoom = zoom.max, min zoom 0, max zoom = max + extra).
 * Client-only: loaded through `next/dynamic` with `ssr: false` because Leaflet touches `window`.
 */
import { useEffect, useRef } from "react";
import * as L from "leaflet";
import "leaflet/dist/leaflet.css";
import {
  blockCentreLatLng,
  SQUAREMAP_TILE_SIZE,
  toBlock,
  toLatLng,
} from "../lib/squaremapCrs";
import type { BlockPos } from "../lib/routeSchema";
import type { WorldSettingsZoom } from "../lib/squaremapSettings";
import styles from "./MapView.module.css";

/** 1x1 transparent PNG, standing in for squaremap's `errorTileUrl: "images/clear.png"`. */
const CLEAR_PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

export interface MapViewProps {
  /** Tile URL template, or null while the world is unknown. */
  tileTemplate: string | null;
  /** Changes whenever the world (and therefore the view) should be reset. */
  worldKey: string;
  zoom: WorldSettingsZoom;
  spawn: { x: number; z: number };
  routePoints: readonly BlockPos[] | null;
  /** Inclusive segment range to highlight (segment i = points[i] -> points[i+1]). */
  highlight: [number, number] | null;
  from: { x: number; z: number } | null;
  to: { x: number; z: number } | null;
  onMapClick: (block: { x: number; z: number }) => void;
}

export default function MapView(props: MapViewProps) {
  const { tileTemplate, worldKey, zoom, spawn, routePoints, highlight, from, to, onMapClick } = props;
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileRef = useRef<L.TileLayer | null>(null);
  const routeLayerRef = useRef<L.LayerGroup | null>(null);
  const endpointLayerRef = useRef<L.LayerGroup | null>(null);
  const highlightLayerRef = useRef<L.LayerGroup | null>(null);
  const maxZoomRef = useRef(zoom.max);
  const clickRef = useRef(onMapClick);

  useEffect(() => {
    clickRef.current = onMapClick;
  }, [onMapClick]);

  // Create the map once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const map = L.map(el, {
      crs: L.CRS.Simple,
      center: [0, 0],
      attributionControl: false,
      preferCanvas: true,
    });
    map.on("click", (e: L.LeafletMouseEvent) => {
      clickRef.current(toBlock(e.latlng, maxZoomRef.current));
    });
    routeLayerRef.current = L.layerGroup().addTo(map);
    highlightLayerRef.current = L.layerGroup().addTo(map);
    endpointLayerRef.current = L.layerGroup().addTo(map);
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
    maxZoomRef.current = zoom.max;
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
    const c = toLatLng(spawn.x, spawn.z, zoom.max);
    map
      .setView([c.lat, c.lng], zoom.def)
      .setMinZoom(0)
      .setMaxZoom(zoom.max + zoom.extra);
    // worldKey intentionally drives the reset; spawn/zoom values are part of it.
  }, [tileTemplate, worldKey, zoom.max, zoom.def, zoom.extra, spawn.x, spawn.z]);

  // Route polyline.
  useEffect(() => {
    const map = mapRef.current;
    const layer = routeLayerRef.current;
    if (!map || !layer) return;
    layer.clearLayers();
    if (!routePoints || routePoints.length === 0) return;
    const latlngs = routePoints.map((p) => {
      const ll = blockCentreLatLng(p.x, p.z, zoom.max);
      return L.latLng(ll.lat, ll.lng);
    });
    L.polyline(latlngs, { color: "#1a56db", weight: 6, opacity: 0.85, interactive: false }).addTo(layer);
    L.polyline(latlngs, { color: "#93c5fd", weight: 2, opacity: 0.9, interactive: false }).addTo(layer);
    const bounds = L.latLngBounds(latlngs);
    if (bounds.isValid()) {
      map.fitBounds(bounds, { padding: [48, 48], maxZoom: zoom.max });
    }
  }, [routePoints, zoom.max]);

  // Highlighted step segment(s).
  useEffect(() => {
    const layer = highlightLayerRef.current;
    if (!layer) return;
    layer.clearLayers();
    if (!routePoints || !highlight) return;
    const [a, b] = highlight;
    const pts = routePoints.slice(a, b + 2).map((p) => {
      const ll = blockCentreLatLng(p.x, p.z, zoom.max);
      return L.latLng(ll.lat, ll.lng);
    });
    if (pts.length < 2) return;
    L.polyline(pts, { color: "#f59e0b", weight: 9, opacity: 0.95, interactive: false }).addTo(layer);
  }, [routePoints, highlight, zoom.max]);

  // Start / end markers (from the route when present, else the pending inputs).
  useEffect(() => {
    const layer = endpointLayerRef.current;
    if (!layer) return;
    layer.clearLayers();
    const first = routePoints?.[0];
    const last = routePoints?.[routePoints.length - 1];
    const start = first ?? from;
    const end = last ?? to;
    const add = (p: { x: number; z: number } | null | undefined, color: string, label: string) => {
      if (!p) return;
      const ll = blockCentreLatLng(p.x, p.z, zoom.max);
      L.circleMarker([ll.lat, ll.lng], {
        radius: 8,
        color: "#fff",
        weight: 3,
        fillColor: color,
        fillOpacity: 1,
        interactive: true,
        bubblingMouseEvents: true,
      })
        .bindTooltip(`${label}: ${p.x}, ${p.z}`)
        .addTo(layer);
    };
    add(start, "#16a34a", "Start");
    add(end, "#dc2626", "Destination");
  }, [routePoints, from, to, zoom.max]);

  return <div ref={containerRef} className={styles.map} role="application" aria-label="Map" />;
}
