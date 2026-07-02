/**
 * Knowledge detail View.
 *
 * Pure presentation: renders the KB Overview (name, id, status, createdAt,
 * updatedAt). Does NOT fabricate statistics (non-goal). When apiKeyReady=false
 * shows a clear but non-blocking Ant Design Alert explaining that documents may
 * still be uploaded in 22.5 (the Create-API-Key button stays disabled and is
 * wired in 22.6 — not in this task).
 *
 * Covers loading / not-found / error / ready states.
 */
import { type JSX } from 'react';
import {
  Card,
  Descriptions,
  Alert,
  Button,
  Space,
  Typography,
  Skeleton,
  Result,
  Tag,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, WarningFilled } from '@ant-design/icons';
import { useNavigate } from 'react-router';
import type { KnowledgeDetailViewModel } from '@app/knowledge/use-knowledge-detail';

const { Title } = Typography;

interface KnowledgeDetailViewProps {
  readonly viewModel: KnowledgeDetailViewModel;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 19).replace('T', ' ');
}

export function KnowledgeDetailView({ viewModel }: KnowledgeDetailViewProps): JSX.Element {
  const navigate = useNavigate();
  const kb = viewModel.knowledgeBase;

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/app/knowledge')}
          aria-label="返回列表"
        >
          返回
        </Button>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => {
            void viewModel.refetch();
          }}
          aria-label="刷新"
        >
          刷新
        </Button>
      </Space>

      {viewModel.status === 'loading' ? (
        <Card>
          <Skeleton active paragraph={{ rows: 4 }} />
        </Card>
      ) : viewModel.status === 'not-found' ? (
        <Result
          status="404"
          title="知识库不存在"
          subTitle="该知识库可能已被删除或无权访问。"
          extra={
            <Button type="primary" onClick={() => navigate('/app/knowledge')}>
              返回列表
            </Button>
          }
        />
      ) : viewModel.status === 'error' ? (
        <Result
          status="error"
          title="加载失败"
          subTitle={viewModel.errorMessage ?? '请稍后重试'}
          extra={
            <Button
              onClick={() => {
                void viewModel.refetch();
              }}
            >
              重试
            </Button>
          }
        />
      ) : kb ? (
        <Card>
          <Title level={3} style={{ marginTop: 0 }}>
            {kb.name}
          </Title>

          {viewModel.awaitingReadiness ? (
            <Alert
              type="warning"
              showIcon
              icon={<WarningFilled />}
              message="API Key 尚未就绪"
              description="Access Scope 正在初始化。文档仍可上传（22.5），但创建 API Key 暂不可用；页面将在就绪后自动刷新。"
              style={{ marginBottom: 16 }}
            />
          ) : null}

          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="名称">{kb.name}</Descriptions.Item>
            <Descriptions.Item label="ID">{kb.id}</Descriptions.Item>
            <Descriptions.Item label="状态">
              {kb.apiKeyReady ? (
                <Tag color="success">API Key 就绪</Tag>
              ) : (
                <Tag color="warning">API Key 待就绪</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">{formatDate(kb.createdAt)}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{formatDate(kb.updatedAt)}</Descriptions.Item>
          </Descriptions>
        </Card>
      ) : (
        // Defensive: should not happen once status==='ready'.
        <Card>
          <Skeleton active />
        </Card>
      )}
    </div>
  );
}
