/**
 * Default AuthServiceDeps bound to the production Console client singleton and
 * the module-scoped SessionStore. ViewModels and the bootstrap resolve deps
 * from here; tests inject their own.
 */
import { consoleClient } from '@services/http/console-client';
import { defaultSessionStore } from '@services/http/session-store';
import type { AuthServiceDeps } from './auth-service';

export const defaultAuthDeps: AuthServiceDeps = {
  client: consoleClient,
  sessionStore: defaultSessionStore,
};
