import { describe, expect, it } from "vitest";
import {
  buildDirections,
  classifyTurn,
  compassDir,
  formatDuration,
  signedTurnAngle,
  walkingSeconds,
} from "./directions";
import type { BlockPos } from "./routeSchema";

const p = (x: number, z: number, y = 64): BlockPos => ({ x, y, z });

describe("compassDir (north = -z, east = +x)", () => {
  const cases: Array<[number, number, string]> = [
    [0, -10, "north"],
    [10, -10, "northeast"],
    [10, 0, "east"],
    [10, 10, "southeast"],
    [0, 10, "south"],
    [-10, 10, "southwest"],
    [-10, 0, "west"],
    [-10, -10, "northwest"],
  ];
  for (const [dx, dz, dir] of cases) {
    it(`(${dx}, ${dz}) -> ${dir}`, () => {
      expect(compassDir(dx, dz)).toBe(dir);
    });
  }
  it("rounds to the nearest of 8 directions", () => {
    expect(compassDir(1, -10)).toBe("north");
    expect(compassDir(10, -4)).toBe("east"); // bearing ~68.2° -> east (> 67.5°)
    expect(compassDir(10, -5)).toBe("northeast"); // bearing ~63.4°
  });
});

describe("turns in Minecraft coordinates", () => {
  it("heading east then south is a right turn", () => {
    expect(signedTurnAngle(1, 0, 0, 1)).toBeCloseTo(90);
    const steps = buildDirections([p(0, 0), p(10, 0), p(10, 10)]);
    expect(steps[1]!.action).toBe("Turn right");
  });

  it("heading east then north is a left turn", () => {
    expect(signedTurnAngle(1, 0, 0, -1)).toBeCloseTo(-90);
    const steps = buildDirections([p(0, 0), p(10, 0), p(10, -10)]);
    expect(steps[1]!.action).toBe("Turn left");
  });

  it("heading north then east is a right turn; north then west is left", () => {
    expect(buildDirections([p(0, 0), p(0, -10), p(10, -10)])[1]!.action).toBe("Turn right");
    expect(buildDirections([p(0, 0), p(0, -10), p(-10, -10)])[1]!.action).toBe("Turn left");
  });

  it("classifies slight / turn / sharp thresholds", () => {
    expect(classifyTurn(0)).toBe("straight");
    expect(classifyTurn(14.9)).toBe("straight");
    expect(classifyTurn(-14.9)).toBe("straight");
    expect(classifyTurn(15)).toBe("slight_right");
    expect(classifyTurn(-30)).toBe("slight_left");
    expect(classifyTurn(44.9)).toBe("slight_right");
    expect(classifyTurn(45)).toBe("right");
    expect(classifyTurn(-90)).toBe("left");
    expect(classifyTurn(135)).toBe("right");
    expect(classifyTurn(135.1)).toBe("sharp_right");
    expect(classifyTurn(-170)).toBe("sharp_left");
  });

  it("produces slight and sharp steps from geometry", () => {
    // east, then 30° towards south (right)
    const slight = buildDirections([p(0, 0), p(100, 0), p(200, 58)]);
    expect(slight[1]!.kind).toBe("slight_right");
    expect(slight[1]!.action).toBe("Slight right");
    // east, then back towards west-north-west (sharp left, ~153°)
    const sharp = buildDirections([p(0, 0), p(100, 0), p(0, -50)]);
    expect(sharp[1]!.kind).toBe("sharp_left");
    expect(sharp[1]!.action).toBe("Sharp left");
  });
});

describe("buildDirections", () => {
  it("starts with a compass heading and ends with arrive", () => {
    const steps = buildDirections([p(0, 0), p(0, 20), p(-20, 20)]);
    expect(steps[0]).toMatchObject({ kind: "depart", action: "Head south", distance: 20, segments: [0, 0] });
    expect(steps[1]).toMatchObject({ kind: "right", action: "Turn right", distance: 20, segments: [1, 1] });
    expect(steps[2]).toMatchObject({ kind: "arrive", text: "Arrive at destination", segments: null });
    expect(steps).toHaveLength(3);
  });

  it("collapses nearly-straight waypoints into the previous step", () => {
    const steps = buildDirections([p(0, 0), p(50, 0), p(100, 5), p(150, 5), p(150, 60)]);
    // (50,0)->(100,5) is ~5.7° and (100,5)->(150,5) is ~-5.7°: both straight.
    expect(steps.map((s) => s.kind)).toEqual(["depart", "right", "arrive"]);
    expect(steps[0]!.segments).toEqual([0, 2]);
    expect(steps[0]!.distance).toBeCloseTo(50 + Math.hypot(50, 5) + 50);
    expect(steps[1]!.segments).toEqual([3, 3]);
  });

  it("appends climb / descend when |dy| >= 2 over a step", () => {
    const steps = buildDirections([p(0, 0, 64), p(10, 0, 70), p(10, 10, 61), p(0, 10, 62)]);
    expect(steps[0]!.text).toBe("Head east, climb 6 blocks");
    expect(steps[1]!.text).toBe("Turn right, descend 9 blocks");
    expect(steps[2]!.text).toBe("Turn right"); // dy = 1 -> no suffix
    expect(steps[0]!.distance).toBeCloseTo(Math.hypot(10, 6));
  });

  it("handles a 2-point route", () => {
    const steps = buildDirections([p(12, -40, 64), p(12, -140, 64)]);
    expect(steps).toHaveLength(2);
    expect(steps[0]).toMatchObject({ kind: "depart", text: "Head north", distance: 100, segments: [0, 0] });
    expect(steps[1]!.kind).toBe("arrive");
  });

  it("handles a single-point route (from == to)", () => {
    const steps = buildDirections([p(5, 5)]);
    expect(steps).toEqual([expect.objectContaining({ kind: "arrive" })]);
  });

  it("merges purely vertical segments without inventing turns", () => {
    const steps = buildDirections([p(0, 0, 60), p(0, 0, 64), p(0, 10, 64), p(0, 10, 66), p(0, 20, 66)]);
    expect(steps.map((s) => s.kind)).toEqual(["depart", "arrive"]);
    expect(steps[0]).toMatchObject({ action: "Head south", dy: 6, segments: [0, 3] });
  });
});

describe("walking time", () => {
  it("uses 4.317 blocks/s", () => {
    expect(walkingSeconds(4.317)).toBeCloseTo(1);
    expect(formatDuration(walkingSeconds(4317))).toBe("16 min 40 s");
    expect(formatDuration(3725)).toBe("1 h 2 min");
    expect(formatDuration(12.4)).toBe("12 s");
  });
});
