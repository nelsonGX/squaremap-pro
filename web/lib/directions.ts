/**
 * Turn-by-turn directions derived client-side from route leg `points` (route schema v2: integer
 * block `{x, z}`, no y). Pure.
 *
 * Coordinate system (Minecraft): +x = east, -x = west, +z = south, -z = north. Looking down on the
 * map with north up (as squaremap draws it: `toLatLng(x, z) = latLng(-z*scale, x*scale)`, so -z is
 * up and +x is right), the (x, z) plane is exactly a screen plane with x to the right and z
 * downwards — a *left-handed* (y-down) system.
 *
 * Compass bearing (clockwise from north): bearing = atan2(dx, -dz).
 *   north (0,-1) -> atan2(0, 1) = 0°; east (1,0) -> atan2(1, 0) = 90°;
 *   south (0, 1) -> atan2(0,-1) = 180°; west (-1,0) -> -90° = 270°.
 *
 * Relative turn: for incoming heading a and outgoing heading b (x/z vectors),
 *   cross = a.x * b.z - a.z * b.x,  dot = a.x * b.x + a.z * b.z,  angle = atan2(cross, dot).
 * In a y-down plane a positive cross product is a *clockwise* rotation as seen on screen, and
 * clockwise from your heading (viewed from above) is to your right. Check: heading east a=(1,0),
 * then south b=(0,1): cross = 1*1 - 0*0 = 1 > 0 -> right. A player facing east has south on their
 * right hand (facing east, north is to the left). Heading east then north b=(0,-1): cross = -1 -> left.
 * So angle > 0 = right, angle < 0 = left.
 *
 * Classification of |angle|: < 15° straight; 15°..<45° slight; 45°..135° turn; > 135° sharp.
 * Straight waypoints (and zero-length segments) are collapsed into the previous step.
 */
import type { XZ } from "./api/types";

export type CompassDir =
  | "north"
  | "northeast"
  | "east"
  | "southeast"
  | "south"
  | "southwest"
  | "west"
  | "northwest";

export type TurnKind =
  | "straight"
  | "slight_left"
  | "slight_right"
  | "left"
  | "right"
  | "sharp_left"
  | "sharp_right";

export type StepKind = "depart" | TurnKind | "arrive";

export interface Step {
  kind: StepKind;
  /** Short action label, e.g. "Head east", "Turn right", "Arrive at destination". */
  action: string;
  /** Full instruction text. */
  text: string;
  /** 2D length of the step's segments in blocks (0 for arrive). */
  distance: number;
  /** Segment index range [firstSegment, lastSegment] (segment i = points[i] -> points[i+1]); null for arrive. */
  segments: [number, number] | null;
}

const COMPASS: readonly CompassDir[] = [
  "north",
  "northeast",
  "east",
  "southeast",
  "south",
  "southwest",
  "west",
  "northwest",
];

/** Bearing in degrees [0, 360), clockwise from north (-z). */
export function bearing(dx: number, dz: number): number {
  const deg = (Math.atan2(dx, -dz) * 180) / Math.PI;
  return ((deg % 360) + 360) % 360;
}

/** 8-point compass direction for an x/z heading. */
export function compassDir(dx: number, dz: number): CompassDir {
  const idx = Math.round(bearing(dx, dz) / 45) % 8;
  return COMPASS[idx]!;
}

/** Signed turn angle in degrees (-180, 180]; positive = right, negative = left (see file doc). */
export function signedTurnAngle(ax: number, az: number, bx: number, bz: number): number {
  const cross = ax * bz - az * bx;
  const dot = ax * bx + az * bz;
  return (Math.atan2(cross, dot) * 180) / Math.PI;
}

export function classifyTurn(angleDeg: number): TurnKind {
  const a = Math.abs(angleDeg);
  if (a < 15) return "straight";
  const right = angleDeg > 0;
  if (a < 45) return right ? "slight_right" : "slight_left";
  if (a <= 135) return right ? "right" : "left";
  return right ? "sharp_right" : "sharp_left";
}

const TURN_LABEL: Record<TurnKind, string> = {
  straight: "Continue straight",
  slight_left: "Slight left",
  slight_right: "Slight right",
  left: "Turn left",
  right: "Turn right",
  sharp_left: "Sharp left",
  sharp_right: "Sharp right",
};

export function turnLabel(kind: TurnKind): string {
  return TURN_LABEL[kind];
}

function segLen(a: XZ, b: XZ): number {
  return Math.hypot(b.x - a.x, b.z - a.z);
}

interface Draft {
  kind: StepKind;
  action: string;
  distance: number;
  segments: [number, number];
}

function finish(d: Draft): Step {
  return { ...d, text: d.action };
}

export function buildDirections(points: readonly XZ[]): Step[] {
  const steps: Step[] = [];
  let current: Draft | null = null;
  // Last horizontal heading (x/z) that had non-zero length.
  let heading: { x: number; z: number } | null = null;

  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i]!;
    const b = points[i + 1]!;
    const dx = b.x - a.x;
    const dz = b.z - a.z;
    const len = segLen(a, b);
    const horizontal = dx !== 0 || dz !== 0;

    let startNew = false;
    let kind: StepKind = "straight";
    let action = "";
    if (horizontal) {
      if (heading === null) {
        startNew = true;
        kind = "depart";
        action = `Head ${compassDir(dx, dz)}`;
      } else {
        const turn = classifyTurn(signedTurnAngle(heading.x, heading.z, dx, dz));
        if (turn !== "straight") {
          startNew = true;
          kind = turn;
          action = TURN_LABEL[turn];
        }
      }
      heading = { x: dx, z: dz };
    }

    if (startNew && kind === "depart" && current !== null) {
      // Leading zero-length segment(s) already opened the departure step: name it now.
      current.action = action;
      current.distance += len;
      current.segments = [current.segments[0], i];
    } else if (startNew || current === null) {
      if (current !== null) steps.push(finish(current));
      current = {
        // A leading zero-length segment becomes part of the departure step.
        kind: startNew ? kind : "depart",
        action: startNew ? action : "Head out",
        distance: len,
        segments: [i, i],
      };
    } else {
      current.distance += len;
      current.segments = [current.segments[0], i];
    }
  }
  if (current !== null) steps.push(finish(current));

  steps.push({
    kind: "arrive",
    action: "Arrive at destination",
    text: "Arrive at destination",
    distance: 0,
    segments: null,
  });
  return steps;
}

/**
 * Minecraft walking speed in blocks per second. ASSUMPTION: 4.317 blocks/s is the commonly cited
 * vanilla walking speed on flat ground (no sprinting, no effects); real travel time varies with
 * terrain, jumping and sprinting.
 */
export const WALK_SPEED_BLOCKS_PER_SECOND = 4.317;

export function walkingSeconds(distance: number): number {
  return distance / WALK_SPEED_BLOCKS_PER_SECOND;
}

export function formatDuration(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h > 0) return `${h} h ${m} min`;
  if (m > 0) return `${m} min ${r} s`;
  return `${r} s`;
}
