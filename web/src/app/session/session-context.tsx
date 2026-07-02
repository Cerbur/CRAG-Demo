/**
 * Session React context.
 *
 * Holds the current {@link AuthSession} (or null), the bootstrap status, and
 * commands to set/clear the session. The shell's account menu, the protected
 * route and the auth pages all read from this context.
 *
 * The context value is provided by {@link SessionProvider} (mounted inside
 * AppProviders above the router). The bootstrap logic runs in
 * {@link useSessionBootstrap} which is invoked once on mount by the provider.
 */
import { createContext, type JSX, type ReactNode, useContext } from 'react';
import type { AuthSession } from '@entities/session';

export type SessionStatus = 'loading' | 'authenticated' | 'anonymous';

export interface SessionContextValue {
  readonly status: SessionStatus;
  readonly session: AuthSession | null;
  /** Called by login/register ViewModels after a successful authentication. */
  setSession(session: AuthSession): void;
  /** Called by logout after clearing local state. */
  clearSession(): void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({
  value,
  children,
}: {
  value: SessionContextValue;
  children: ReactNode;
}): JSX.Element {
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSessionContext(): SessionContextValue {
  const v = useContext(SessionContext);
  if (!v) {
    throw new Error('useSessionContext must be used inside <SessionProvider>');
  }
  return v;
}
