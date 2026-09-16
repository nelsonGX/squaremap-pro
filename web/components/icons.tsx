/**
 * Inline SVG icons for the map chrome, drawn in the SF Symbols register Apple Maps uses: a 24×24
 * grid, a single consistent 1.8px stroke with round caps and joins, generous interior space, and
 * filled shapes only where SF Symbols itself fills (the directions glyph, the person glyphs).
 * `currentColor` keeps them themeable from CSS.
 */
import type { SVGProps } from "react";

type IconProps = Omit<SVGProps<SVGSVGElement>, "children"> & { size?: number };

function svgProps({ size = 22, ...rest }: IconProps) {
  return { width: size, height: size, viewBox: "0 0 24 24", "aria-hidden": true, focusable: false, ...rest } as const;
}

/** Shared stroke geometry, so every outlined glyph has the same optical weight. */
const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

/** SF Symbols `line.3.horizontal`. */
export function MenuIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <path d="M4 7.5h16M4 12h16M4 16.5h16" />
    </svg>
  );
}

/** SF Symbols `magnifyingglass`. */
export function SearchIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke} strokeWidth="2">
      <circle cx="10.75" cy="10.75" r="6.25" />
      <path d="m15.5 15.5 4 4" />
    </svg>
  );
}

/** SF Symbols `xmark`. */
export function CloseIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke} strokeWidth="2">
      <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />
    </svg>
  );
}

/** SF Symbols `chevron.backward` — Apple navigates back with a chevron, not an arrow. */
export function BackIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke} strokeWidth="2.2">
      <path d="M14.5 5 8 12l6.5 7" />
    </svg>
  );
}

/** SF Symbols `arrow.triangle.turn.up.right.diamond.fill`, Apple Maps' directions badge. */
export function DirectionsIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="currentColor">
      <path d="M12.72 2.3a1.02 1.02 0 0 0-1.44 0L2.3 11.28a1.02 1.02 0 0 0 0 1.44l8.98 8.98a1.02 1.02 0 0 0 1.44 0l8.98-8.98a1.02 1.02 0 0 0 0-1.44zm.62 5.53 3.1 3.1a.8.8 0 0 1 0 1.14l-3.1 3.1a.6.6 0 0 1-1.03-.42v-1.83h-2.4v2.7a.9.9 0 0 1-1.8 0v-3.5a1.1 1.1 0 0 1 1.1-1.1h3.1V8.25a.6.6 0 0 1 1.03-.42z" />
    </svg>
  );
}

/** SF Symbols `arrow.up.arrow.down` — Apple's swap affordance. */
export function SwapIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <path d="M8 19.5V5M8 5 4.5 8.5M8 5l3.5 3.5M16 4.5V19M16 19l-3.5-3.5M16 19l3.5-3.5" />
    </svg>
  );
}

/** SF Symbols `scope`, used for "pick this point on the map". */
export function PinIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <circle cx="12" cy="12" r="5.75" />
      <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
      <path d="M12 2.75v2.25M12 19v2.25M2.75 12H5M19 12h2.25" />
    </svg>
  );
}

/** SF Symbols `pencil`. */
export function PencilIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <path d="M4 20h3.6L19.3 8.3a2.55 2.55 0 0 0-3.6-3.6L4 16.4z" />
      <path d="m14.6 5.8 3.6 3.6" />
    </svg>
  );
}

/** SF Symbols `person.2.fill`, for the live-players layer. */
export function PeopleIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="currentColor">
      <path d="M9.2 11.4a3.45 3.45 0 1 0 0-6.9 3.45 3.45 0 0 0 0 6.9zm0 1.4c-2.9 0-5.9 1.45-5.9 3.4v2.3a.9.9 0 0 0 .9.9h10a.9.9 0 0 0 .9-.9v-2.3c0-1.95-3-3.4-5.9-3.4z" />
      <path d="M16.6 10.8a2.9 2.9 0 1 0 0-5.8 2.9 2.9 0 0 0-1.35.33 5.1 5.1 0 0 1 0 5.14c.41.21.87.33 1.35.33zM17.3 12.3c-.5 0-1 .04-1.47.11 1.2.83 1.97 1.98 1.97 3.39v2.3c0 .17-.02.34-.07.5h2.67a.9.9 0 0 0 .9-.9v-2.1c0-1.82-1.9-3.3-4-3.3z" />
    </svg>
  );
}

/** SF Symbols `square.3.layers.3d`, for the world/layer picker. */
export function LayersIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <path d="m12 3.25 8.25 4.6-8.25 4.6-8.25-4.6z" />
      <path d="m3.9 12.3 8.1 4.5 8.1-4.5" />
      <path d="m3.9 16.6 8.1 4.5 8.1-4.5" opacity="0.5" />
    </svg>
  );
}

/** SF Symbols `person.crop.circle` — Apple's account glyph. */
export function PersonIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} {...stroke}>
      <circle cx="12" cy="12" r="9.1" />
      <circle cx="12" cy="9.6" r="3.1" />
      <path d="M5.9 19.1a6.6 6.6 0 0 1 12.2 0" />
    </svg>
  );
}
