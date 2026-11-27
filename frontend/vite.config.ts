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
  if (cert && key) {
    server.https = { cert: readFileSync(cert), key: readFileSync(key) };
    server.fs = { allow: [process.cwd()] };
  }
  server.proxy = {
    "^/edge-api/": {
      target,
      changeOrigin: true,
      rewrite: (path) => path.slice("/edge-api".length),
      configure: (proxy) => {
        proxy.on("proxyReq", (outgoing, incoming) => {
          outgoing.removeHeader("X-TMC-Client-IP");
          if (incoming.socket.remoteAddress) {
            outgoing.setHeader("X-TMC-Client-IP", incoming.socket.remoteAddress);
          }
        });
        if (!server.https) return;
        // The local backend also serves loopback HTTP. Cookies on the HTTPS side stay Secure.
        proxy.on("proxyRes", (response) => {
          const cookies = response.headers["set-cookie"];
          if (cookies)
            response.headers["set-cookie"] = cookies.map((cookie) =>
              /(?:^|;)\s*Secure(?:;|$)/i.test(cookie) ? cookie : `${cookie}; Secure`,
            );
        });
      },
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
