/**
 * API Keys index View — standalone aggregation page.
 *
 * Renders the flattened key list across all of the user's KnowledgeBases, with
 * a KB backlink (→ /app/knowledge/{knowledgeBaseId}) on each row. Partial
 * failures (some KBs' key fetches failed) are surfaced as a non-blocking
 * warning; successful KBs' keys still render.
 *
 * There is NO create/action UI here by design (plan_22 §22.6 non-goal: no
 * global search or batch ops). The user manages keys per-KB via the detail tab.
 * This page is read-only aggregation for visibility.
 */
import { type JSX } from 'react';
import { Alert, Button, Empty, Skeleton, Space, Table, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Link } from 'react-router';
import type { ColumnsType } from 'antd/es/table';
import type { ApiKeyIndexViewModel, KbKeyBucket } from '@app/api-keys/use-api-key-index';
import type { ApiKeyItem, ApiKeyDisplayStatus } from '../model/mapper';
import type { KnowledgeBaseLite } from '@app/api-keys/types';

const { Text, Title } = Typography;

interface ApiKeysIndexViewProps {
  readonly viewModel: ApiKeyIndexViewModel;
}

function statusColour(status: ApiKeyDisplayStatus): string {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'DISABLED':
      return 'warning';
    case 'REVOKED':
    case 'EXPIRED':
      return 'error';
    default:
      return 'default';
  }
}

function statusLabel(status: ApiKeyDisplayStatus): string {
  switch (status) {
    case 'ACTIVE':
      return '启用中';
    case 'DISABLED':
      return '已禁用';
    case 'REVOKED':
      return '已撤销';
    case 'EXPIRED':
      return '已过期';
    default:
      return status;
  }
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 19).replace('T', ' ');
}

/** Row for the index table: includes the KB backlink. */
interface IndexRow extends ApiKeyItem {
  readonly knowledgeBaseName: string;
}

export function ApiKeysIndexView({ viewModel }: ApiKeysIndexViewProps): JSX.Element {
  const rows: IndexRow[] = viewModel.items.map((k) => {
    const bucket = viewModel.buckets.find((b) => b.knowledgeBase.id === k.knowledgeBaseId);
    return { ...k, knowledgeBaseName: bucket?.knowledgeBase.name ?? k.knowledgeBaseId };
  });

  const columns: ColumnsType<IndexRow> = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      width: 200,
      render: (name: string) => <Text ellipsis={{ tooltip: name }}>{name}</Text>,
    },
    {
      title: '知识库',
      dataIndex: 'knowledgeBaseId',
      key: 'kb',
      width: 200,
      render: (kbId: string, record: IndexRow) => (
        <Link to={`/app/knowledge/${kbId}`}>{record.knowledgeBaseName}</Link>
      ),
    },
    {
      title: '前缀',
      dataIndex: 'keyPrefix',
      key: 'keyPrefix',
      width: 140,
      render: (prefix: string) => <Text code>{prefix}</Text>,
    },
    {
      title: '状态',
      dataIndex: 'statusForDisplay',
      key: 'status',
      width: 100,
      render: (status: ApiKeyDisplayStatus) => (
        <Tag color={statusColour(status)}>{statusLabel(status)}</Tag>
      ),
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 180,
      render: (iso: string | null) => formatDate(iso),
    },
  ];

  const failedBuckets = viewModel.buckets.filter((b) => b.errorMessage !== null);

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <Title level={3} style={{ margin: 0 }}>
          API Keys
        </Title>
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
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : viewModel.status === 'error' ? (
        <Alert
          type="error"
          showIcon
          title="加载知识库失败"
          description={viewModel.errorMessage ?? '请稍后重试'}
          action={
            <Button icon={<ReloadOutlined />} onClick={() => void viewModel.refetch()}>
              重试
            </Button>
          }
        />
      ) : (
        <>
          {failedBuckets.length > 0 ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              title="部分知识库的密钥加载失败"
              description={
                <ul style={{ margin: 0, paddingLeft: 20 }}>
                  {failedBuckets.map((b: KbKeyBucket) => (
                    <li key={b.knowledgeBase.id}>
                      <KnowledgeBaseBacklink kb={b.knowledgeBase} />：{b.errorMessage}
                    </li>
                  ))}
                </ul>
              }
            />
          ) : null}

          {viewModel.status === 'empty' ? (
            <Empty description="还没有任何 API Key" />
          ) : (
            <>
              <div className="api-keys-index-desktop-table">
                <Table<IndexRow>
                  rowKey="id"
                  columns={columns}
                  dataSource={rows}
                  pagination={false}
                  size="small"
                />
              </div>
              <ul
                className="api-keys-index-mobile-list"
                style={{ display: 'none', padding: 0, listStyle: 'none' }}
              >
                {rows.map((item) => (
                  <li
                    key={item.id}
                    style={{
                      padding: '12px 0',
                      borderBottom: '1px solid #f0f0f0',
                    }}
                  >
                    <Space orientation="vertical" size={4} style={{ width: '100%' }}>
                      <Space style={{ justifyContent: 'space-between', width: '100%' }}>
                        <Text strong ellipsis>
                          {item.name}
                        </Text>
                        <Tag color={statusColour(item.statusForDisplay)}>
                          {statusLabel(item.statusForDisplay)}
                        </Tag>
                      </Space>
                      <Link to={`/app/knowledge/${item.knowledgeBaseId}`}>
                        {item.knowledgeBaseName}
                      </Link>
                      <Text code style={{ fontSize: 12 }}>
                        {item.keyPrefix}
                      </Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        过期：{formatDate(item.expiresAt)}
                      </Text>
                    </Space>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </div>
  );
}

function KnowledgeBaseBacklink({ kb }: { readonly kb: KnowledgeBaseLite }): JSX.Element {
  return <Link to={`/app/knowledge/${kb.id}`}>{kb.name}</Link>;
}
