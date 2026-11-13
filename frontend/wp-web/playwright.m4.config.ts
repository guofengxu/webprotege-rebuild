import { defineConfig } from "@playwright/test";
import { randomBytes } from "node:crypto";

process.env.WP_M4_ADMIN_PASSWORD ??= randomBytes(18).toString("base64url");

export default defineConfig({
  testDir: "e2e/m4",
  testMatch: "m4-acceptance.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
  use: {
    baseURL: "http://localhost:18080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "zh-CN",
    viewport: { width: 1440, height: 900 },
  },
  webServer: {
    command: "node e2e/m4/start-backend.mjs",
    url: "http://localhost:18080/api/auth/me",
    timeout: 180_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      WP_M4_ADMIN_PASSWORD: process.env.WP_M4_ADMIN_PASSWORD,
    },
  },
});
