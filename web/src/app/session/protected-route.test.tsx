import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntApp } from 'antd';
import { ProtectedRoute } from './protected-route';
import { SessionProvider, type SessionContextValue } from './session-context';

function wrapProviders(session: SessionContextValue, initialPath = '/app/knowledge'): {
  readonly getByText: (t: string) => HTMLElement;
  readonly container: HTMLElement;
} {
  const qc = new QueryClient();
  const utils = render(
    <QueryClientProvider client={qc}>
      <ConfigProvider>
        <AntApp>
          <SessionProvider value={session}>
            <MemoryRouter initialEntries={[initialPath]}>
              <Routes>
                <Route element={<ProtectedRoute />}>
                  <Route path="/app/knowledge" element={<div>protected-content</div>} />
                </Route>
                <Route path="/login" element={<div>login-page</div>} />
              </Routes>
            </MemoryRouter>
          </SessionProvider>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { getByText: utils.getByText, container: utils.container };
}

describe('ProtectedRoute', () => {
  it('renders a spinner while loading', () => {
    const session: SessionContextValue = {
      status: 'loading',
      session: null,
      setSession: () => {},
      clearSession: () => {},
    };
    const { container } = wrapProviders(session);
    // Ant Design Spin renders an .ant-spin element.
    expect(container.querySelector('.ant-spin')).toBeTruthy();
  });

  it('redirects to /login when anonymous', () => {
    const session: SessionContextValue = {
      status: 'anonymous',
      session: null,
      setSession: () => {},
      clearSession: () => {},
    };
    const { getByText } = wrapProviders(session);
    expect(getByText('login-page')).toBeTruthy();
  });

  it('renders the protected outlet when authenticated', () => {
    const session: SessionContextValue = {
      status: 'authenticated',
      session: {
        userId: '1001',
        nickname: 'alice',
        tenantId: '2001',
        role: 'OWNER',
      },
      setSession: () => {},
      clearSession: () => {},
    };
    const { getByText } = wrapProviders(session);
    expect(getByText('protected-content')).toBeTruthy();
  });
});
