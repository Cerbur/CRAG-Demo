import { createBrowserRouter, Navigate } from 'react-router';
import { ROUTE_PATHS } from './routes';
import { AppShell } from './shell';
import { ProtectedRoute } from './session/protected-route';
import {
  LoginPage,
  RegisterPage,
  KnowledgeListPage,
  KnowledgeDetailPage,
  ApiKeysPage,
  ChatPage,
  NotFoundPage,
} from '../pages';

/** Router instance type returned by createBrowserRouter. */
export type AppRouter = ReturnType<typeof createBrowserRouter>;

/**
 * Creates the application router.
 *
 * Public routes (/login, /register) render directly. The /app/* surface is
 * wrapped by {@link ProtectedRoute} which gates on the session bootstrap
 * status: loading → spinner, anonymous → redirect to /login, authenticated →
 * render the matched child route via <Outlet/>.
 *
 * The / index redirects to the knowledge list, which itself is protected; an
 * anonymous user lands on /login after the protected-route redirect.
 */
export function createAppRouter(): AppRouter {
  return createBrowserRouter([
    {
      path: '/',
      element: <AppShell />,
      children: [
        { index: true, element: <Navigate to={ROUTE_PATHS.knowledgeList} replace /> },
        { path: ROUTE_PATHS.login, element: <LoginPage /> },
        { path: ROUTE_PATHS.register, element: <RegisterPage /> },
        {
          element: <ProtectedRoute />,
          children: [
            { path: ROUTE_PATHS.knowledgeList, element: <KnowledgeListPage /> },
            { path: ROUTE_PATHS.knowledgeDetail, element: <KnowledgeDetailPage /> },
            { path: ROUTE_PATHS.apiKeys, element: <ApiKeysPage /> },
            { path: ROUTE_PATHS.chat, element: <ChatPage /> },
          ],
        },
        { path: ROUTE_PATHS.notFound, element: <NotFoundPage /> },
      ],
    },
  ]);
}
