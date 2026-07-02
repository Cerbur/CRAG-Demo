import { createBrowserRouter, Navigate } from 'react-router';
import { ROUTE_PATHS } from './routes';
import { AppShell } from './shell';
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
 * Creates the application router. 22.1 returns a createBrowserRouter instance
 * configured with all 6 canonical routes plus a 404 catch-all, all wrapped by
 * the desktop/mobile App Shell. 22.3 replaces these placeholders with real
 * protected-route logic.
 */
export function createAppRouter(): AppRouter {
  return createBrowserRouter([
    {
      path: '/',
      element: <AppShell />,
      children: [
        { index: true, element: <Navigate to={ROUTE_PATHS.login} replace /> },
        { path: ROUTE_PATHS.login, element: <LoginPage /> },
        { path: ROUTE_PATHS.register, element: <RegisterPage /> },
        { path: ROUTE_PATHS.knowledgeList, element: <KnowledgeListPage /> },
        { path: ROUTE_PATHS.knowledgeDetail, element: <KnowledgeDetailPage /> },
        { path: ROUTE_PATHS.apiKeys, element: <ApiKeysPage /> },
        { path: ROUTE_PATHS.chat, element: <ChatPage /> },
        { path: ROUTE_PATHS.notFound, element: <NotFoundPage /> },
      ],
    },
  ]);
}
