/**
 * squaremap's block <-> Leaflet LatLng transform, ported from squaremap v1.3.12
 * `web/src/js/Squaremap.js` (map uses `crs: L.CRS.Simple`):
 *
 *   toLatLng(x, z) { return L.latLng(this.pixelsToMeters(-z), this.pixelsToMeters(x)); }
 *   toPoint(latlng) { return L.point(this.metersToPixels(latlng.lng), this.metersToPixels(-latlng.lat)); }
 *   pixelsToMeters(num) { return num * this.scale; }
 *   metersToPixels(num) { return num / this.scale; }
 *   setScale(zoom) { this.scale = 1 / Math.pow(2, zoom); ... }
 *
 * and `World.load`: `S.setScale(this.zoom.max);` — the scale is derived from the world's
 * `zoom.max`. With CRS.Simple (transformation (1, 0, -1, 0), scale(zoom) = 2^zoom) this makes one
 * block equal one screen pixel at map zoom `zoom.max`.
 *
 * Pure functions: no Leaflet import, so they are unit-testable without a DOM. `LatLngLike` is
 * structurally compatible with `L.LatLng` / `L.LatLngLiteral`.
 */

export interface LatLngLike {
  lat: number;
  lng: number;
}

export interface BlockXZ {
  x: number;
  z: number;
}

/** squaremap `setScale(zoom)`: `1 / Math.pow(2, zoom)`. */
export function scaleFor(maxZoom: number): number {
  return 1 / Math.pow(2, maxZoom);
}

/** squaremap `toLatLng(x, z)` = `latLng(-z * scale, x * scale)`. Accepts fractional blocks. */
export function toLatLng(x: number, z: number, maxZoom: number): LatLngLike {
  const scale = scaleFor(maxZoom);
  return { lat: -z * scale, lng: x * scale };
}

/** squaremap `toPoint(latlng)` = `point(lng / scale, -lat / scale)` — fractional block coords. */
export function toPoint(latLng: LatLngLike, maxZoom: number): BlockXZ {
  const scale = scaleFor(maxZoom);
  // `0 - v` avoids producing -0 for lat === 0.
  return { x: latLng.lng / scale, z: 0 - latLng.lat / scale };
}

/**
 * LatLng -> integer block coordinate. squaremap's `UICoordinates` floors the `toPoint` result:
 * `this.x = ... Math.floor(point.x); this.z = ... Math.floor(point.y);`
 */
export function toBlock(latLng: LatLngLike, maxZoom: number): BlockXZ {
  const p = toPoint(latLng, maxZoom);
  return { x: Math.floor(p.x), z: Math.floor(p.z) };
}

/** Block centre (x + 0.5, z + 0.5, per the route schema) as LatLng. */
export function blockCentreLatLng(x: number, z: number, maxZoom: number): LatLngLike {
  return toLatLng(x + 0.5, z + 0.5, maxZoom);
}

/**
 * squaremap tile URL template (`LayerControl.createTileLayer`):
 * `tiles/${world.name}/{z}/{x}_{y}.png` with `tileSize: 512, minNativeZoom: 0,
 * maxNativeZoom: world.zoom.max`. `baseUrl` is the squaremap web root (no trailing slash needed).
 */
export function tileUrlTemplate(baseUrl: string, worldWebName: string): string {
  const base = baseUrl.replace(/\/+$/, "");
  return `${base}/tiles/${worldWebName}/{z}/{x}_{y}.png`;
}

/** squaremap tile size (`tileSize: 512`; server `Image.SIZE = 512`). */
export const SQUAREMAP_TILE_SIZE = 512;
