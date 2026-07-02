/**
 * useLogout — best-effort logout command used by the account menu.
 *
 * Flow:
 *   1. POST /auth/logout (best-effort; the server clears the cookie in its
 *      finally block regardless of Origin outcome).
 *   2. defaultSessionStore.clear() — drop the in-memory Access Token.
 *   3. queryClient.clear() — invalidate the entire TanStack Query cache so no
 *      stale per-tenant data leaks across sessions.
 *   4. onLoggedOut callback — the shell navigates to /login.
 *
 * Never throws; failures of the network call are swallowed because the local
 * session is cleared unconditionally.
 */
import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { AuthServiceDeps } from './auth-service';
import { logout } from './auth-service';
import { defaultAuthDeps } from './default-deps';

export function useLogout(options?: {
  readonly deps?: AuthServiceDeps;
  readonly onLoggedOut?: () => void;
}): () => Promise<void> {
  const deps = options?.deps ?? defaultAuthDeps;
  const queryClient = useQueryClient();

  return useCallback(async () => {
    await logout(deps);
    // Clear the TanStack Query cache so no stale tenant-scoped data survives.
    queryClient.clear();
    options?.onLoggedOut?.();
  }, [deps, queryClient, options]);
}
