import { useState, type JSX, type ReactNode } from 'react';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cragTheme } from './theme';
import { ErrorBoundary } from './error-boundary';

interface AppProvidersProps {
  children: ReactNode;
}

/**
 * Root providers for the CRAG Web Console.
 *
 * Layers, outermost to innermost:
 *  1. ErrorBoundary — catches uncaught render errors.
 *  2. QueryClientProvider — TanStack Query server-state cache.
 *  3. ConfigProvider + AntApp — Ant Design theme, locale, message/modal API.
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
          <AntApp>{children}</AntApp>
        </ConfigProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
