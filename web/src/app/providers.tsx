import { useCallback, useState, type JSX, type ReactNode } from 'react';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cragTheme } from './theme';
import { ErrorBoundary } from './error-boundary';
import {
  SessionProvider,
  type SessionContextValue,
} from './session/session-context';
import { useSessionBootstrap } from './session/use-session-bootstrap';
import type { AuthSession } from '@entities/session';

interface AppProvidersProps {
  children: ReactNode;
}

/**
 * Inner component that runs the bootstrap hook (hooks cannot be called
 * conditionally; this component is always mounted when AppProviders mounts).
 * It holds the session state and feeds it into the SessionProvider.
 */
function SessionBootstrapProvider({ children }: { children: ReactNode }): JSX.Element {
  const bootstrap = useSessionBootstrap();
  const [override, setOverride] = useState<{
    readonly status: SessionContextValue['status'];
    readonly session: AuthSession | null;
  } | null>(null);

  const setSession = useCallback((session: AuthSession): void => {
    setOverride({ status: 'authenticated', session });
  }, []);

  const clearSession = useCallback((): void => {
    setOverride({ status: 'anonymous', session: null });
  }, []);

  // Once a login/logout override has been applied, the bootstrap result is
  // ignored — the user has actively changed auth state. Otherwise we surface
  // the bootstrap-derived status/session.
  const status: SessionContextValue['status'] = override?.status ?? bootstrap.status;
  const session: AuthSession | null = override?.session ?? bootstrap.session;
  const value: SessionContextValue = { status, session, setSession, clearSession };

  return <SessionProvider value={value}>{children}</SessionProvider>;
}

/**
 * Root providers for the CRAG Web Console.
 *
 * Layers, outermost to innermost:
 *  1. ErrorBoundary — catches uncaught render errors.
 *  2. QueryClientProvider — TanStack Query server-state cache.
 *  3. ConfigProvider + AntApp — Ant Design theme, locale, message/modal API.
 *  4. SessionProvider — current AuthSession + bootstrap status (22.3).
 *
 * Routing is provided by createAppRouter() + <RouterProvider/> which consumers
 * place as the child of AppProviders; AppProviders intentionally does NOT add
 * a BrowserRouter so the data router (createBrowserRouter) owns navigation.
 *
 * The QueryClient is created once per app mount via useState so that HMR in
 * development does not reset the cache on every render.
 */
export function AppProviders({ children }: AppProvidersProps): JSX.Element {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: false,
            refetchOnWindowFocus: false,
            staleTime: 30_000,
          },
          mutations: {
            retry: false,
          },
        },
      }),
  );

  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ConfigProvider theme={cragTheme} locale={zhCN}>
          <AntApp>
            <SessionBootstrapProvider>{children}</SessionBootstrapProvider>
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
