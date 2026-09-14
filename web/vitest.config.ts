import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // All tests are pure (no DOM).
    environment: "node",
    include: ["lib/**/*.test.ts"],
  },
});
