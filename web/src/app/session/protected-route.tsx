/**
 * ProtectedRoute — guards /app/* routes.
 *
 *   loading      → stable Ant Design centered Spin.
 *   anonymous    → <Navigate to="/login" state={{ from }} replace/>.
 *   authenticated → render <Outlet/>.
 *
 * The `from` location is passed in login state so the login page can redirect
 * back to the originally requested page after successful authentication.
 */
import type { JSX } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { Spin } from 'antd';
import { useSessionContext } from './session-context';

export function ProtectedRoute(): JSX.Element {
  const { status } = useSessionContext();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div
        style={{
          minHeight: '60vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Spin size="large" aria-label="加载中" />
      </div>
    );
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <Outlet />;
}
