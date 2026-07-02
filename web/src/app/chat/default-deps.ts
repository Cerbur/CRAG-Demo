/**
 * Default ChatServiceDeps bound to the production Open client singleton.
 * ViewModels resolve deps from here; tests inject their own.
 */
import { openClient } from '@services/http/open-client';
import type { ChatServiceDeps } from './chat-service';

export const defaultChatDeps: ChatServiceDeps = {
  client: openClient,
};
