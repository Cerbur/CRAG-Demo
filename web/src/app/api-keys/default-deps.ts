/**
 * Default ApiKeyServiceDeps bound to the production Console client singleton.
 * ViewModels resolve deps from here; tests inject their own.
 */
import { consoleClient } from '@services/http/console-client';
import type { ApiKeyServiceDeps } from './api-key-service';

export const defaultApiKeyDeps: ApiKeyServiceDeps = {
  client: consoleClient,
};
