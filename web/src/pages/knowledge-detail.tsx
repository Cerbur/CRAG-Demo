import type { JSX } from 'react';
import { useParams } from 'react-router';
import { KnowledgeDetailView } from '@features/knowledge/components/knowledge-detail-view';
import { useKnowledgeDetail } from '@app/knowledge/use-knowledge-detail';
import { useSessionContext } from '@app/session/session-context';

/**
 * Knowledge detail page. Reads the route param `knowledgeBaseId` and the active
 * session's tenantId, then wires the detail ViewModel to the detail View.
 *
 * Polling stops automatically on unmount (TanStack Query unsubscribes) and when
 * `apiKeyReady === true`.
 */
export function KnowledgeDetailPage(): JSX.Element {
  const params = useParams<{ knowledgeBaseId: string }>();
  const knowledgeBaseId = params.knowledgeBaseId ?? '';
  const { session } = useSessionContext();
  const tenantId = session?.tenantId ?? '';
  const viewModel = useKnowledgeDetail({ tenantId, knowledgeBaseId });
  return <KnowledgeDetailView viewModel={viewModel} />;
}
