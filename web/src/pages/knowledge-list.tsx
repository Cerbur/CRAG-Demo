import type { JSX } from 'react';
import { KnowledgeListView } from '@features/knowledge/components/knowledge-list-view';
import { useKnowledgeList } from '@app/knowledge/use-knowledge-list';
import { useSessionContext } from '@app/session/session-context';

/**
 * Knowledge list page. Wires the list ViewModel to the list View. The tenant
 * scope comes from the active AuthSession.
 */
export function KnowledgeListPage(): JSX.Element {
  const { session } = useSessionContext();
  const tenantId = session?.tenantId ?? '';
  const viewModel = useKnowledgeList({ tenantId });
  return <KnowledgeListView viewModel={viewModel} tenantId={tenantId} />;
}
