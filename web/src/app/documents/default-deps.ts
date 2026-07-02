/**
 * Default DocumentServiceDeps bound to the production Console client
 * singleton. ViewModels resolve deps from here; tests inject their own.
 */
import { consoleClient } from '@services/http/console-client';
import type { DocumentServiceDeps } from './document-service';

export const defaultDocumentDeps: DocumentServiceDeps = {
  client: consoleClient,
};
