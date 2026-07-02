import type { JSX } from 'react';
import { useParams } from 'react-router';
import { PagePlaceholder } from './page-placeholder';

export function KnowledgeDetailPage(): JSX.Element {
  const params = useParams<{ knowledgeBaseId: string }>();
  const id = params.knowledgeBaseId ?? '';
  return (
    <PagePlaceholder
      title="知识库详情"
      description={`knowledgeBaseId=${id}（详情 Overview / Documents / API Keys 在 22.4–22.6 实现）`}
    />
  );
}
