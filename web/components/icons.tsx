/**
 * Inline SVG icons for the map chrome. Drawn on a 24×24 grid in the Material style Google Maps uses,
 * so they line up with each other at any size; `currentColor` keeps them themeable from CSS.
 */
import type { SVGProps } from "react";

type IconProps = Omit<SVGProps<SVGSVGElement>, "children"> & { size?: number };

function svgProps({ size = 22, ...rest }: IconProps) {
  return { width: size, height: size, viewBox: "0 0 24 24", "aria-hidden": true, focusable: false, ...rest } as const;
}

export function MenuIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  );
}

export function SearchIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.6-3.6" />
    </svg>
  );
}

export function CloseIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M6 6l12 12M18 6 6 18" />
    </svg>
  );
}

export function BackIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 12H4M10 6l-6 6 6 6" />
    </svg>
  );
}

/** Google Maps' directions chevron. */
export function DirectionsIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="currentColor">
      <path d="M12.7 2.3a1 1 0 0 0-1.4 0l-9 9a1 1 0 0 0 0 1.4l9 9a1 1 0 0 0 1.4 0l9-9a1 1 0 0 0 0-1.4zM13 15v-2.5h-2.5V15L7 11.5 10.5 8v2.5H14a1 1 0 0 1 1 1V15z" />
    </svg>
  );
}

export function SwapIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 4v16M7 4 3.5 7.5M7 4l3.5 3.5M17 20V4M17 20l-3.5-3.5M17 20l3.5-3.5" />
    </svg>
  );
}

export function PinIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="12" cy="12" r="6.5" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
    </svg>
  );
}

export function PencilIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16z" />
      <path d="m14.5 5.5 4 4" />
    </svg>
  );
}

export function PeopleIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="currentColor">
      <path d="M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zm0 1.5c-2.8 0-6 1.4-6 3.4V19h12v-3.1c0-2-3.2-3.4-6-3.4zM16.6 12.7c1.4.9 2.4 2 2.4 3.2V19h3v-2.8c0-1.6-2.3-2.8-5.4-3.5zM16 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0-1.4.3 5 5 0 0 1 0 6.4c.4.2.9.3 1.4.3z" />
    </svg>
  );
}

export function LayersIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinejoin="round">
      <path d="m12 3 9 5-9 5-9-5z" />
      <path d="m3.5 12.5 8.5 4.7 8.5-4.7" />
    </svg>
  );
}

export function PersonIcon(p: IconProps) {
  return (
    <svg {...svgProps(p)} fill="currentColor">
      <path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 1.8c-3.2 0-7 1.7-7 3.9V20h14v-2.3c0-2.2-3.8-3.9-7-3.9z" />
    </svg>
  );
}
