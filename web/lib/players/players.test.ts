import { describe, expect, it } from "vitest";
import { parsePlayerList } from "../api/guards";
import { fixturePlayers } from "../api/fixtures";
import { playerColour, playersInWorld, playerTitle, screenHeading } from "./players";
import type { OnlinePlayer } from "../api/types";

const player = (over: Partial<OnlinePlayer> = {}): OnlinePlayer => ({
  uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  name: "Steve",
  world: "minecraft:overworld",
  x: 12,
  y: 64,
  z: -40,
  yaw: 0,
  ...over,
});

describe("screenHeading", () => {
  it("points down the screen for yaw 0 (facing +z)", () => {
    expect(screenHeading(0)).toBe(180);
  });

  it("points left for yaw 90 (facing -x)", () => {
    expect(screenHeading(90)).toBe(270);
  });

  it("points up for yaw 180 (facing -z)", () => {
    expect(screenHeading(180)).toBe(0);
  });

  it("normalises out-of-range yaw", () => {
    expect(screenHeading(-90)).toBe(90);
    expect(screenHeading(540)).toBe(0);
  });
});

describe("playersInWorld", () => {
  it("filters by world and sorts by name", () => {
    const list = [
      player({ uuid: "b", name: "Zoe" }),
      player({ uuid: "c", name: "Alex", world: "minecraft:the_nether" }),
      player({ uuid: "a", name: "Ada" }),
    ];
    expect(playersInWorld(list, "minecraft:overworld").map((p) => p.name)).toEqual(["Ada", "Zoe"]);
  });

  it("is empty without a world", () => {
    expect(playersInWorld([player()], null)).toEqual([]);
  });
});

describe("playerTitle", () => {
  it("shows the name and the block position", () => {
    expect(playerTitle(player())).toBe("Steve · 12, 64, -40");
  });
});

describe("playerColour", () => {
  it("is stable per uuid and differs between players", () => {
    expect(playerColour("a-uuid")).toBe(playerColour("a-uuid"));
    expect(playerColour("a-uuid")).not.toBe(playerColour("b-uuid"));
  });
});

describe("fixture players", () => {
  it("parse as a valid player list and stay in the overworld", () => {
    const parsed = parsePlayerList(fixturePlayers("minecraft:overworld", 1_700_000_000_000));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.players).toHaveLength(3);
    expect(parsed.value.max).toBe(20);
    for (const p of parsed.value.players) {
      expect(p.world).toBe("minecraft:overworld");
      expect(p.yaw).toBeGreaterThanOrEqual(0);
      expect(p.yaw).toBeLessThan(360);
    }
  });

  it("is empty in other worlds", () => {
    const parsed = parsePlayerList(fixturePlayers("minecraft:the_nether", 0));
    expect(parsed.ok && parsed.value.players).toEqual([]);
  });
});
