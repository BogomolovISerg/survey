import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Контекст приложения в Tomcat (например /survey/). При сборке из Maven передаётся через VITE_SURVEY_CONTEXT_PATH.
const base = process.env.VITE_SURVEY_CONTEXT_PATH ?? "/";

export default defineConfig({
  base: base.endsWith("/") ? base : `${base}/`,
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // локальная разработка: фронт на 5173, API на 8080
      "/api": { target: process.env.SURVEY_API_PROXY ?? "http://localhost:8080", changeOrigin: true },
    },
  },
  build: {
    outDir: "../target/generated-resources/frontend",
    emptyOutDir: true,
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
