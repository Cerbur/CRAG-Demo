import type { JSX } from 'react';
import { ApiKeysIndexView } from '@features/api-keys/components/api-keys-index-view';
import { useApiKeyIndex } from '@app/api-keys/use-api-key-index';
import { useSessionContext } from '@app/session/session-context';

/**
 * Standalone API Keys index page (/app/api-keys).
 *
 * Aggregates keys across all of the user's KnowledgeBases using a worker pool
 * capped at concurrency 4 (plan_22 §22.6). Partial failures are surfaced as
 * non-blocking warnings; successful KBs' keys still render. This page is
 * read-only aggregation — key lifecycle actions happen on the per-KB detail
 * tab.
 */
export function ApiKeysPage(): JSX.Element {
  const { session } = useSessionContext();
  const tenantId = session?.tenantId ?? '';
  const viewModel = useApiKeyIndex({ tenantId });
  return <ApiKeysIndexView viewModel={viewModel} />;
}
