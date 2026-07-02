// CRAG Web Console runtime server.
//
// 22.1 ships a minimal placeholder so `pnpm start` is a no-op-safe command.
// 22.9 implements static serving, SPA fallback, /health, and the same-origin
// /console-api and /open-api proxies with Cookie Path rewriting.
//
// This file is intentionally side-effect free until 22.9 wires the real server;
// running it now simply reports that the runtime is not yet available.

const PORT = Number(process.env.WEB_PORT ?? 3000);

if (process.env.NODE_ENV !== 'production' && !process.env.WEB_RUNTIME_FORCE_STUB) {
  console.warn(
    `[crag-web] server.mjs is a 22.1 stub. The runtime server ships in 22.9. ` +
      `For local development run \`pnpm dev\` instead. (PORT=${PORT})`,
  );
}

export {};
