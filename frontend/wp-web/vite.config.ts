import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

function apiProxy() {
  return {
    "/api": {
      target: "http://localhost:8080",
      changeOrigin: false,
    },
  };
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: apiProxy(),
  },
  preview: {
    proxy: apiProxy(),
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
  },
});
