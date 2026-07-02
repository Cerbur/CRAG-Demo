/**
 * useSessionBootstrap — restore the user's session on page load using the
 * HttpOnly refresh cookie.
 *
 * Flow:
 *   1. On mount, call restoreFromCookie(deps).
 *   2. restoreFromCookie hits GET /auth/me; the Console client auto-refreshes
 *      on 401 via the cookie (single-flight). If refresh succeeds, /me replays
 *      with the user projection and we additionally fetch /tenants to recover
 *      the working tenant context.
 *   3. On any failure (no cookie / refresh failed / cross-site origin / empty
 *      tenant list) → status 'anonymous', session null.
 *
 * The hook returns `{ status, session }`. Callers (the SessionProvider) feed
 * these into the SessionContext.
 */
import { useEffect, useState } from 'react';
import type { AuthSession } from '@entities/session';
import { restoreFromCookie, type AuthServiceDeps } from './auth-service';
import { defaultAuthDeps } from './default-deps';
import type { SessionStatus } from './session-context';

export interface SessionBootstrapResult {
  readonly status: SessionStatus;
  readonly session: AuthSession | null;
}

export function useSessionBootstrap(options?: {
  readonly deps?: AuthServiceDeps;
}): SessionBootstrapResult {
  const deps = options?.deps ?? defaultAuthDeps;
  const [result, setResult] = useState<SessionBootstrapResult>({
    status: 'loading',
    session: null,
  });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const session = await restoreFromCookie(deps);
      if (cancelled) return;
      setResult(
        session
          ? { status: 'authenticated', session }
          : { status: 'anonymous', session: null },
      );
    })();
    return () => {
      cancelled = true;
    };
    // Bootstrap intentionally runs once on mount; deps is captured at mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return result;
}
