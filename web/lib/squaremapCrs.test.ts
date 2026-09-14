import { describe, expect, it } from "vitest";
import {
  blockCentreLatLng,
  scaleFor,
  tileUrlTemplate,
  toBlock,
  toLatLng,
  toPoint,
} from "./squaremapCrs";

describe("squaremapCrs", () => {
  it("scale is 1 / 2^maxZoom (squaremap setScale)", () => {
    expect(scaleFor(0)).toBe(1);
    expect(scaleFor(3)).toBe(0.125);
    expect(scaleFor(5)).toBe(1 / 32);
  });

  it("matches a hand-computed case from squaremap's formula", () => {
    // maxZoom 3 -> scale 1/8; toLatLng(100, -40) = latLng(-(-40)/8, 100/8) = (5, 12.5)
    expect(toLatLng(100, -40, 3)).toEqual({ lat: 5, lng: 12.5 });
    // toPoint((5, 12.5)) = point(12.5*8, -5*8) = (100, -40)
    expect(toPoint({ lat: 5, lng: 12.5 }, 3)).toEqual({ x: 100, z: -40 });
    // Block centre (100.5, -39.5): lat = 39.5/8 = 4.9375, lng = 100.5/8 = 12.5625
    expect(blockCentreLatLng(100, -40, 3)).toEqual({ lat: 4.9375, lng: 12.5625 });
    // CRS.Simple projects latlng at zoom Z to pixel (lng*2^Z, -lat*2^Z): at Z = maxZoom the
    // pixel is the block coordinate itself.
    const ll = toLatLng(100, -40, 3);
    expect([ll.lng * 2 ** 3, -ll.lat * 2 ** 3]).toEqual([100, -40]);
  });

  it("north (-z) is up (positive lat), east (+x) is right (positive lng)", () => {
    expect(toLatLng(0, -16, 2).lat).toBeGreaterThan(0);
    expect(toLatLng(16, 0, 2).lng).toBeGreaterThan(0);
  });

  const zooms = [0, 1, 3, 5, 8];
  const blocks: Array<[number, number]> = [
    [0, 0],
    [1, -1],
    [-1, 1],
    [12, -40],
    [-513, -1024],
    [310, 95],
    [-29_999_999, 29_999_999],
  ];

  for (const zoom of zooms) {
    it(`round-trips block -> LatLng -> block at maxZoom ${zoom}`, () => {
      for (const [x, z] of blocks) {
        expect(toBlock(toLatLng(x, z, zoom), zoom)).toEqual({ x, z });
        // Block centres floor back to the same block.
        expect(toBlock(blockCentreLatLng(x, z, zoom), zoom)).toEqual({ x, z });
      }
    });
  }

  it("floors fractional positions like squaremap UICoordinates (Math.floor)", () => {
    expect(toBlock(toLatLng(-0.25, -0.75, 3), 3)).toEqual({ x: -1, z: -1 });
    expect(toBlock(toLatLng(4.99, 7.01, 3), 3)).toEqual({ x: 4, z: 7 });
  });

  it("builds squaremap's tile URL template", () => {
    expect(tileUrlTemplate("http://localhost:8080/", "minecraft_overworld")).toBe(
      "http://localhost:8080/tiles/minecraft_overworld/{z}/{x}_{y}.png",
    );
    expect(tileUrlTemplate("/mock-squaremap", "minecraft_the_end")).toBe(
      "/mock-squaremap/tiles/minecraft_the_end/{z}/{x}_{y}.png",
    );
  });
});
