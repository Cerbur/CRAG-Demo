/**
 * Documents tab View.
 *
 * Pure presentation: renders the upload zone, the document list (desktop table
 * / mobile structured list), status tags, failure detail, and conditional
 * retry action. The ViewModel is supplied by the parent (knowledge-detail page)
 * via {@link DocumentsViewModel} from app/documents.
 *
 * Status colour mapping (ui-style):
 *  - PENDING / PROCESSING → blue (active)
 *  - READY → green (success)
 *  - FAILED → red (error)
 *
 * Mobile: renders a structured list (no horizontal scroll). Desktop: Ant Design
 * Table with stable column widths.
 *
 * The upload zone uses a hidden file input + Ant Design Upload.Dragger with
 * `beforeUpload` returning false so the actual HTTP call goes through the
 * ViewModel (consoleClient multipart), never a raw fetch.
 */
import { type JSX, useRef, type ChangeEvent } from 'react';
import {
  Alert,
  Button,
  Empty,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
  App as AntApp,
} from 'antd';
import { InboxOutlined, ReloadOutlined, RedoOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { DocumentsViewModel } from '@app/documents/use-documents';
import type { DocumentItem, IngestionStatus } from '../model/mapper';

const { Text } = Typography;

interface DocumentsTabViewProps {
  readonly viewModel: DocumentsViewModel;
}

/** Status → tag colour. */
function statusColour(status: IngestionStatus): string {
  switch (status) {
    case 'READY':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'PENDING':
    case 'PROCESSING':
      return 'processing';
    default:
      return 'default';
  }
}

/** Human-readable status label. */
function statusLabel(status: IngestionStatus): string {
  switch (status) {
    case 'PENDING':
      return '待处理';
    case 'PROCESSING':
      return '处理中';
    case 'READY':
      return '就绪';
    case 'FAILED':
      return '失败';
    default:
      return status;
  }
}

/** Format bytes as a human-readable string. */
function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

/** Format an ISO timestamp; fall back to the raw string on parse failure. */
function formatDate(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 19).replace('T', ' ');
}

export function DocumentsTabView({ viewModel }: DocumentsTabViewProps): JSX.Element {
  const { message } = AntApp.useApp();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFile = async (file: File): Promise<void> => {
    try {
      await viewModel.upload.uploadFile(file);
      message.success('上传成功');
    } catch {
      // The ViewModel surfaces the error via `upload.error`; no toast here to
      // avoid duplicating the server message.
    } finally {
      viewModel.upload.reset();
    }
  };

  const onInputChange = (e: ChangeEvent<HTMLInputElement>): void => {
    const file = e.target.files?.[0];
    if (file) {
      void handleFile(file);
    }
    // Reset so picking the same file again still fires onChange.
    e.target.value = '';
  };

  const columns: ColumnsType<DocumentItem> = [
    {
      title: '文件名',
      dataIndex: 'filename',
      key: 'filename',
      ellipsis: true,
      width: 240,
      render: (filename: string) => <Text ellipsis={{ tooltip: filename }}>{filename}</Text>,
    },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      key: 'sizeBytes',
      width: 100,
      render: (size: number) => formatBytes(size),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: IngestionStatus) => (
        <Tag color={statusColour(status)}>{statusLabel(status)}</Tag>
      ),
    },
    {
      title: '尝试',
      dataIndex: 'attempt',
      key: 'attempt',
      width: 80,
      render: (attempt: number) => attempt,
    },
    {
      title: '失败原因',
      dataIndex: 'failureMessage',
      key: 'failureMessage',
      ellipsis: true,
      render: (msg: string | null) =>
        msg ? <Text type="danger">{msg}</Text> : <Text type="secondary">—</Text>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (iso: string | null) => formatDate(iso),
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_: unknown, record: DocumentItem) => {
        if (!viewModel.canRetry(record)) return null;
        return (
          <Button
            size="small"
            icon={<RedoOutlined />}
            loading={viewModel.retry.isPending}
            onClick={() => {
              viewModel.retry
                .retryDocument(record.id)
                .catch(() => {})
                .finally(() => viewModel.retry.reset());
            }}
          >
            重试
          </Button>
        );
      },
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Upload.Dragger
          accept=".txt,.md,text/plain,text/markdown"
          showUploadList={false}
          beforeUpload={(file) => {
            void handleFile(file);
            return false; // Prevent Ant Design's own upload; we use the ViewModel.
          }}
          disabled={viewModel.upload.isPending}
          style={{ padding: '16px 24px' }}
        >
          <p style={{ margin: 0, fontSize: 14 }}>
            <InboxOutlined style={{ marginRight: 8 }} />
            点击或拖拽 .txt / .md 文件上传（≤ 10 MiB）
          </p>
        </Upload.Dragger>
        {/* Hidden input as an additional accessible path; the Dragger is the
            primary affordance. */}
        <input
          ref={fileInputRef}
          type="file"
          accept=".txt,.md"
          style={{ display: 'none' }}
          onChange={onInputChange}
        />
      </Space>

      {viewModel.upload.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={
            viewModel.upload.error instanceof Error ? viewModel.upload.error.message : '上传失败'
          }
          closable
          onClose={() => viewModel.upload.reset()}
        />
      ) : null}

      {viewModel.retry.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={
            viewModel.retry.error instanceof Error ? viewModel.retry.error.message : '重试失败'
          }
          closable
          onClose={() => viewModel.retry.reset()}
        />
      ) : null}

      {viewModel.status === 'loading' ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : viewModel.status === 'error' ? (
        <Alert
          type="error"
          showIcon
          title="加载失败"
          description={viewModel.errorMessage ?? '请稍后重试'}
          action={
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                void viewModel.refetch();
              }}
            >
              重试
            </Button>
          }
        />
      ) : viewModel.status === 'empty' ? (
        <Empty description="还没有文档" />
      ) : (
        <>
          {/* Desktop table — hidden on small screens via CSS. */}
          <div className="documents-desktop-table">
            <Table<DocumentItem>
              rowKey="row"
              columns={columns}
              dataSource={viewModel.items.map((d) => ({ ...d, row: d.id }))}
              pagination={false}
              size="small"
              scroll={{ x: 'max-content' }}
            />
          </div>
          {/* Mobile structured list — shown on small screens. */}
          <ul
            className="documents-mobile-list"
            style={{ display: 'none', padding: 0, listStyle: 'none' }}
          >
            {viewModel.items.map((d) => (
              <li
                key={d.id}
                style={{
                  padding: '12px 0',
                  borderBottom: '1px solid #f0f0f0',
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: '4px 12px',
                  alignItems: 'center',
                }}
              >
                <Text strong style={{ flex: '1 1 100%' }} ellipsis>
                  {d.filename}
                </Text>
                <Tag color={statusColour(d.status)}>{statusLabel(d.status)}</Tag>
                <Text type="secondary">{formatBytes(d.sizeBytes)}</Text>
                <Text type="secondary">尝试 {d.attempt}</Text>
                <Text type="secondary">{formatDate(d.updatedAt)}</Text>
                {d.failureMessage ? (
                  <Text type="danger" style={{ flex: '1 1 100%' }}>
                    {d.failureMessage}
                  </Text>
                ) : null}
                {viewModel.canRetry(d) ? (
                  <Button
                    size="small"
                    icon={<RedoOutlined />}
                    loading={viewModel.retry.isPending}
                    onClick={() => {
                      viewModel.retry
                        .retryDocument(d.id)
                        .catch(() => {})
                        .finally(() => viewModel.retry.reset());
                    }}
                  >
                    重试
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

/**
 * Render failure detail for a document as safe text (never raw HTML). Exported
 * for potential reuse and for unit coverage of the safe-rendering rule.
 */
export function DocumentFailureDetail({
  item,
}: {
  readonly item: DocumentItem;
}): JSX.Element | null {
  if (!item.failureMessage || item.status !== 'FAILED') return null;
  return (
    <Text type="danger" style={{ display: 'block', marginTop: 4 }}>
      {item.failureMessage}
    </Text>
  );
}
