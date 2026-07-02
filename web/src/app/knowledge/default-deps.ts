/**
 * Default KnowledgeServiceDeps bound to the production Console client
 * singleton. ViewModels resolve deps from here; tests inject their own.
 */
import { consoleClient } from '@services/http/console-client';
import type { KnowledgeServiceDeps } from './knowledge-service';

export const defaultKnowledgeDeps: KnowledgeServiceDeps = {
  client: consoleClient,
};
