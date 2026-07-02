/**
 * In-memory Access Token store.
 *
 * web/constraints/api-client.md §2: Access Token MUST live only in memory.
 * No localStorage / sessionStorage / cookie / URL. Refresh Token is HttpOnly
 * cookie and is never read by JS.
 *
 * This module is the single owner of the live JWT. The Console transport asks
 * for it via `getAccessToken()` before each request, and the single-flight
 * refresh loop writes a new one via `setAccessToken()`. Refresh failure calls
 * `clear()`, which also notifies subscribers (used by 22.3 to redirect to
 * login and clear the TanStack Query cache).
 *
 * Subscribers are intentionally minimal — only an onChange callback set, no
 * state broadcasting — to avoid leaking the token via React DevTools. The
 * callback receives NO arguments; it must not receive the token.
 */

/** Minimal observer contract — the SessionStore notifies on clear/set only. */
export type SessionChangeListener = () => void;

export interface SessionStore {
  getAccessToken(): string | null;
  setAccessToken(token: string): void;
  clear(): void;
  /** Subscribe to changes (set/clear). Returns an unsubscribe function. */
  subscribe(listener: SessionChangeListener): () => void;
}

/**
 * Create a new in-memory SessionStore. Exported for tests so they can spin up
 * isolated stores without module-singleton bleed-through.
 */
export function createSessionStore(): SessionStore {
  let token: string | null = null;
  const listeners = new Set<SessionChangeListener>();
  const notify = (): void => {
    for (const l of listeners) {
      try {
        l();
      } catch {
        /* listener errors must not break the store */
      }
    }
  };
  return {
    getAccessToken(): string | null {
      return token;
    },
    setAccessToken(next: string): void {
      token = next;
      notify();
    },
    clear(): void {
      const had = token !== null;
      token = null;
      if (had) notify();
    },
    subscribe(listener: SessionChangeListener): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

/**
 * Module-scoped default SessionStore. The Console client imports this directly;
 * production code does not need to thread the store through DI. Tests that need
 * isolation either reset it via `defaultSessionStore.clear()` between cases or
 * construct their own via {@link createSessionStore} and inject into a fresh
 * Console client.
 */
export const defaultSessionStore: SessionStore = createSessionStore();
