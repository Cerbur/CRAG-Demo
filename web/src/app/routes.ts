/**
 * Canonical application route identifiers.
 *
 * The string-literal union is the single source of truth for which top-level
 * paths exist. Dynamic segments (e.g. `/app/knowledge/:knowledgeBaseId`) use a
 * stable placeholder form so route collections and tests can compare sets.
 */
export type AppRoute =
  | '/login'
  | '/register'
  | '/app/knowledge'
  | `/app/knowledge/${string}`
  | '/app/api-keys'
  | '/app/chat';

/** Template form used by React Router. */
export const ROUTE_PATHS = {
  login: '/login',
  register: '/register',
  knowledgeList: '/app/knowledge',
  knowledgeDetail: '/app/knowledge/:knowledgeBaseId',
  apiKeys: '/app/api-keys',
  chat: '/app/chat',
  notFound: '*',
} as const;

/** Set of canonical leaf routes (dynamic detail collapsed to its template). */
export const APP_ROUTES: ReadonlySet<string> = new Set<string>([
  ROUTE_PATHS.login,
  ROUTE_PATHS.register,
  ROUTE_PATHS.knowledgeList,
  ROUTE_PATHS.apiKeys,
  ROUTE_PATHS.chat,
  ROUTE_PATHS.knowledgeDetail,
]);

/**
 * Matches a pathname against the AppRoute union. Returns the matched template
 * (e.g. `/app/knowledge/:knowledgeBaseId`) or null when the path is unknown.
 */
export function matchAppRoute(pathname: string): string | null {
  if (APP_ROUTES.has(pathname)) {
    return pathname;
  }
  const detailMatch = /^\/app\/knowledge\/[^/]+$/.exec(pathname);
  if (detailMatch) {
    return ROUTE_PATHS.knowledgeDetail;
  }
  return null;
}
