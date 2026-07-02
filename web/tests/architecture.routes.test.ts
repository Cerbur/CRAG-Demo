import { describe, it, expect } from 'vitest';
import { createAppRouter } from '../src/app/router';
import { APP_ROUTES, matchAppRoute, type AppRoute } from '../src/app/routes';

describe('app routes', () => {
  it('exports the 6 canonical routes as a set', () => {
    expect(APP_ROUTES.size).toBe(6);
    expect(APP_ROUTES.has('/login')).toBe(true);
    expect(APP_ROUTES.has('/register')).toBe(true);
    expect(APP_ROUTES.has('/app/knowledge')).toBe(true);
    expect(APP_ROUTES.has('/app/api-keys')).toBe(true);
    expect(APP_ROUTES.has('/app/chat')).toBe(true);
  });

  it('matches the dynamic knowledge detail route', () => {
    expect(matchAppRoute('/app/knowledge/123e4567')).toBe('/app/knowledge/:knowledgeBaseId');
    expect(matchAppRoute('/login')).toBe('/login');
    expect(matchAppRoute('/unknown')).toBeNull();
  });

  it('createAppRouter returns a data router instance with a subscribe API', () => {
    const router = createAppRouter();
    expect(router).toBeDefined();
    // react-router v7 data router exposes subscribe + navigate.
    expect(typeof (router as { subscribe?: unknown }).subscribe).toBe('function');
    expect(typeof (router as { navigate?: unknown }).navigate).toBe('function');
  });

  it('the AppRoute type covers exactly the canonical routes (compile-time check)', () => {
    const samples: AppRoute[] = [
      '/login',
      '/register',
      '/app/knowledge',
      '/app/api-keys',
      '/app/chat',
    ];
    expect(samples.every((r) => APP_ROUTES.has(r) || r.startsWith('/app/knowledge/'))).toBe(true);
  });
});
