import tailwindcss from "@tailwindcss/vite";
import { readFileSync } from "node:fs";
import viteReact from "@vitejs/plugin-react";
import { tanstackStart } from "@tanstack/react-start/plugin/vite";
import { defineConfig, loadEnv, type ServerOptions } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "TMC_DEV_");
  const cert = env["TMC_DEV_HTTPS_CERT"];
  const key = env["TMC_DEV_HTTPS_KEY"];
  if (Boolean(cert) !== Boolean(key))
    throw new Error("Set both TMC_DEV_HTTPS_CERT and TMC_DEV_HTTPS_KEY.");
  const target = env["TMC_DEV_PROXY_TARGET"] || "http://127.0.0.1:8084";
  const server: ServerOptions = {};
  if (cert && key) server.https = { cert: readFileSync(cert), key: readFileSync(key) };
  server.proxy = {
    "^/edge-api/": {
      target,
      changeOrigin: true,
      rewrite: (path) => path.slice("/edge-api".length),
    },
  };
  return {
    plugins: [
      tanstackStart({ server: { entry: "server" } }),
      viteReact(),
      tailwindcss(),
      tsconfigPaths(),
    ],
    server,
  };
});
