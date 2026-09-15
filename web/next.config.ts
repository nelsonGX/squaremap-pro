import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export to web/out, served by the mod's Javalin at "/" (same origin as /api and /tiles).
  output: "export",
  // No image optimisation server in a static export.
  images: { unoptimized: true },
  reactStrictMode: true,
  // Do not let `next dev` generate AGENTS.md / CLAUDE.md in web/ (project rules live in the repo root).
  agentRules: false,
  turbopack: {
    // Pin the workspace root to web/ (a stray lockfile higher up would otherwise be picked).
    root: import.meta.dirname,
  },
};

export default nextConfig;
