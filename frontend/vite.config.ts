import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  test: {
    environment: "node",
    exclude: ["e2e/**", "node_modules/**", "dist/**"],
  },
});
