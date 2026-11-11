import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "e2e",
  testIgnore: "m4/**",
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
  use: {
    baseURL: "http://localhost:4173",
  },
  webServer: {
    command:
      "pnpm exec vite build && pnpm exec vite preview --port 4173 --strictPort",
    url: "http://localhost:4173",
    reuseExistingServer: !process.env.CI,
  },
});
