import { defineConfig } from "vitest/config";
import { VitePWA } from "vite-plugin-pwa";

const repositoryName = process.env.GITHUB_REPOSITORY?.split("/")[1];
const base = process.env.GITHUB_ACTIONS && repositoryName ? \`/\${repositoryName}/\` : "/";

export default defineConfig({
  base,
  plugins: [
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["icon.svg"],
      manifest: false,
      workbox: { globPatterns: ["**/*.{js,css,html,svg,webmanifest}"], navigateFallback: "index.html" },
    }),
  ],
});