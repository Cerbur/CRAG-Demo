import { type JSX, useState } from 'react';
import { useParams } from 'react-router';
import { KnowledgeDetailView } from '@features/knowledge/components/knowledge-detail-view';
import { DocumentsTabView } from '@features/documents/components/documents-tab-view';
import { useKnowledgeDetail } from '@app/knowledge/use-knowledge-detail';
import { useDocuments } from '@app/documents/use-documents';
import { useSessionContext } from '@app/session/session-context';
import { Alert } from 'antd';

/** Active detail tab. API Keys tab is reserved for task 22.6. */
type DetailTab = 'overview' | 'documents' | 'api-keys';

const TAB_KEYS: ReadonlyArray<DetailTab> = ['overview', 'documents', 'api-keys'];

/**
 * Knowledge detail page. Reads the route param `knowledgeBaseId` and the active
 * session's tenantId, then renders a tabbed detail: Overview (22.4) | Documents
 * (22.5) | API Keys (22.6 — disabled placeholder).
 *
 * Polling stops automatically on unmount (TanStack Query unsubscribes) and when
 * the polled resource reaches a terminal state.
 */
export function KnowledgeDetailPage(): JSX.Element {
  const params = useParams<{ knowledgeBaseId: string }>();
  const knowledgeBaseId = params.knowledgeBaseId ?? '';
  const { session } = useSessionContext();
  const tenantId = session?.tenantId ?? '';
  const knowledgeVm = useKnowledgeDetail({ tenantId, knowledgeBaseId });
  const documentsVm = useDocuments({ tenantId, knowledgeBaseId });
  const [activeTab, setActiveTab] = useState<DetailTab>('overview');

  // Render the active tab panel. The tab bar is a simple accessible list of
  // buttons; Ant Design Tabs is avoided for layout determinism.
  const renderTab = (): JSX.Element => {
    if (activeTab === 'documents') {
      return <DocumentsTabView viewModel={documentsVm} />;
    }
    if (activeTab === 'api-keys') {
      return (
        <Alert
          type="info"
          showIcon
          message="API Keys 即将上线"
          description="该 Tab 由任务 22.6 实现。"
        />
      );
    }
    return <KnowledgeDetailView viewModel={knowledgeVm} />;
  };

  return (
    <div>
      <nav
        role="tablist"
        aria-label="知识库详情"
        style={{
          display: 'flex',
          gap: 8,
          borderBottom: '1px solid #f0f0f0',
          marginBottom: 16,
        }}
      >
        {TAB_KEYS.map((key) => {
          const label =
            key === 'overview' ? '概览' : key === 'documents' ? '文档' : 'API Keys';
          const active = activeTab === key;
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={active}
              disabled={key === 'api-keys'}
              onClick={() => setActiveTab(key)}
              style={{
                padding: '8px 16px',
                border: 'none',
                background: 'transparent',
                cursor: key === 'api-keys' ? 'not-allowed' : 'pointer',
                fontWeight: active ? 600 : 400,
                color: active ? '#1677ff' : key === 'api-keys' ? '#bbb' : 'inherit',
                borderBottom: active ? '2px solid #1677ff' : '2px solid transparent',
                marginBottom: '-1px',
              }}
            >
              {label}
            </button>
          );
        })}
      </nav>
      {renderTab()}
    </div>
  );
}
