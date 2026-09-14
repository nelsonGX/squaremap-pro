import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Do not let `next dev` generate AGENTS.md / CLAUDE.md in web/ (project rules live in the repo root).
  agentRules: false,
  turbopack: {
    // Pin the workspace root to web/ (a stray lockfile higher up would otherwise be picked).
    root: import.meta.dirname,
  },
};

export default nextConfig;
