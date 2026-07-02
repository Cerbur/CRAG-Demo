/**
 * MSW server setup helper.
 *
 * Re-exported from services/tests so feature tests (22.3+) get a single,
 * consistent entry point: `startServer(handlers)` returns a started MSW server
 * that they must `close()` in `afterEach` (handled by {@link withMswServer}).
 */
import { setupServer } from 'msw/node';
import type { HttpHandler } from 'msw';

/** Start an MSW server with the given handlers; caller owns lifecycle. */
export function startServer(handlers: ReadonlyArray<HttpHandler>): ReturnType<typeof setupServer> {
  const server = setupServer(...handlers);
  server.listen({ onUnhandledRequest: 'error' });
  return server;
}

/**
 * Run `fn` with an MSW server; close the server after the test body resolves.
 * Designed for `it('...', () => withMswServer(handlers, async () => { ... }))`.
 */
export async function withMswServer(
  handlers: ReadonlyArray<HttpHandler>,
  fn: () => Promise<void>,
): Promise<void> {
  const server = startServer(handlers);
  try {
    await fn();
  } finally {
    server.close();
  }
}
