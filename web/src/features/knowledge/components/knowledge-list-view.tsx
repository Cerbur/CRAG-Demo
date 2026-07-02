/**
 * Knowledge list View.
 *
 * Pure presentation: renders desktop table / mobile list, the create modal
 * trigger, and loading/empty/error states. All data comes from the ViewModel
 * passed in. Does NOT import services/http or parse API envelopes.
 *
 * Columns per MANIFEST (do NOT add file counts, storage, token totals, health,
 * Edit or Delete): Name, Created At, API Key Ready, View.
 *
 * Clicking a row navigates to the detail route. Pagination is Previous/Next via
 * pageToken (NOT numbered total pages).
 */
import { type JSX, useState } from 'react';
import {
  Table,
  Button,
  Space,
  Typography,
  Empty,
  Alert,
  Tag,
  Grid,
  Card,
  Modal,
  Form,
  Input,
  App,
  Skeleton,
} from 'antd';
import { PlusOutlined, ReloadOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router';
import type { KnowledgeListViewModel } from '@app/knowledge/use-knowledge-list';
import { useCreateKnowledgeBase } from '@app/knowledge/use-knowledge-mutations';
import type { KnowledgeBase } from '../model/mapper';

const { Title } = Typography;
const { useBreakpoint } = Grid;

interface KnowledgeListViewProps {
  readonly viewModel: KnowledgeListViewModel;
  /** Tenant id (used to build the detail route). */
  readonly tenantId: string;
}

function formatDate(iso: string): string {
  // Stable, locale-independent formatting. Return the raw ISO if parsing fails.
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 19).replace('T', ' ');
}

export function KnowledgeListView({
  viewModel,
  tenantId,
}: KnowledgeListViewProps): JSX.Element {
  const navigate = useNavigate();
  const screens = useBreakpoint();
  const isDesktop = screens.md === true;
  const [createOpen, setCreateOpen] = useState(false);

  const gotoDetail = (kb: KnowledgeBase): void => {
    navigate(`/app/knowledge/${kb.id}`);
  };

  return (
    <div>
      <Space
        style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}
        align="center"
      >
        <Title level={3} style={{ margin: 0 }}>
          知识库
        </Title>
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              void viewModel.refetch();
            }}
            aria-label="刷新"
          >
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
            aria-label="新建知识库"
          >
            新建
          </Button>
        </Space>
      </Space>

      {viewModel.status === 'loading' ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : viewModel.status === 'error' ? (
        <ErrorState
          message={viewModel.errorMessage ?? '加载失败'}
          onRetry={() => {
            void viewModel.refetch();
          }}
        />
      ) : viewModel.status === 'empty' ? (
        <EmptyState onCreate={() => setCreateOpen(true)} />
      ) : isDesktop ? (
        <DesktopTable viewModel={viewModel} onRowClick={gotoDetail} />
      ) : (
        <MobileList viewModel={viewModel} onRowClick={gotoDetail} />
      )}

      {(viewModel.status === 'ready' || viewModel.status === 'empty') && (
        <Pagination viewModel={viewModel} />
      )}

      <CreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        tenantId={tenantId}
        onCreated={(kb) => {
          setCreateOpen(false);
          navigate(`/app/knowledge/${kb.id}`);
        }}
      />
    </div>
  );
}

function DesktopTable({
  viewModel,
  onRowClick,
}: {
  readonly viewModel: KnowledgeListViewModel;
  readonly onRowClick: (kb: KnowledgeBase) => void;
}): JSX.Element {
  return (
    <Table<KnowledgeBase>
      dataSource={[...viewModel.items]}
      rowKey="id"
      pagination={false}
      onRow={(record) => ({
        onClick: () => onRowClick(record),
        style: { cursor: 'pointer' },
      })}
      aria-label="知识库列表"
      columns={[
        {
          title: '名称',
          dataIndex: 'name',
          key: 'name',
        },
        {
          title: '创建时间',
          dataIndex: 'createdAt',
          key: 'createdAt',
          render: (v: string) => formatDate(v),
        },
        {
          title: 'API Key 就绪',
          dataIndex: 'apiKeyReady',
          key: 'apiKeyReady',
          render: (ready: boolean) =>
            ready ? <Tag color="success">就绪</Tag> : <Tag color="warning">待就绪</Tag>,
        },
        {
          title: '操作',
          key: 'view',
          render: (_: unknown, record: KnowledgeBase) => (
            <Button
              type="link"
              onClick={(e) => {
                e.stopPropagation();
                onRowClick(record);
              }}
            >
              查看
            </Button>
          ),
        },
      ]}
    />
  );
}

function MobileList({
  viewModel,
  onRowClick,
}: {
  readonly viewModel: KnowledgeListViewModel;
  readonly onRowClick: (kb: KnowledgeBase) => void;
}): JSX.Element {
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="small">
      {viewModel.items.map((kb) => (
        <Card
          key={kb.id}
          size="small"
          hoverable
          onClick={() => onRowClick(kb)}
          aria-label={`知识库卡片 ${kb.name}`}
        >
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Typography.Text strong>{kb.name}</Typography.Text>
            <Space size={8} wrap>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {formatDate(kb.createdAt)}
              </Typography.Text>
              {kb.apiKeyReady ? (
                <Tag color="success">就绪</Tag>
              ) : (
                <Tag color="warning">待就绪</Tag>
              )}
            </Space>
          </Space>
        </Card>
      ))}
    </Space>
  );
}

function Pagination({ viewModel }: { readonly viewModel: KnowledgeListViewModel }): JSX.Element {
  return (
    <Space style={{ marginTop: 16, justifyContent: 'flex-end', width: '100%' }}>
      <Button
        disabled={!viewModel.hasPreviousPage}
        onClick={() => viewModel.gotoPreviousPage()}
      >
        上一页
      </Button>
      <Button disabled={!viewModel.hasNextPage} onClick={() => viewModel.gotoNextPage()}>
        下一页
      </Button>
    </Space>
  );
}

function EmptyState({ onCreate }: { readonly onCreate: () => void }): JSX.Element {
  return (
    <Empty
      description="还没有知识库"
      image={Empty.PRESENTED_IMAGE_SIMPLE}
    >
      <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
        新建知识库
      </Button>
    </Empty>
  );
}

function ErrorState({
  message,
  onRetry,
}: {
  readonly message: string;
  readonly onRetry: () => void;
}): JSX.Element {
  return (
    <Alert
      type="error"
      message="加载知识库失败"
      description={
        <Space direction="vertical">
          <span>{message}</span>
          <Button size="small" onClick={onRetry}>
            重试
          </Button>
        </Space>
      }
      showIcon
      icon={<ExclamationCircleOutlined />}
    />
  );
}

interface CreateModalProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly tenantId: string;
  readonly onCreated: (kb: KnowledgeBase) => void;
}

function CreateModal({ open, onClose, tenantId, onCreated }: CreateModalProps): JSX.Element {
  const [form] = Form.useForm<{ name: string }>();
  const { message } = App.useApp();
  const mutation = useCreateKnowledgeBase({ tenantId });

  const handleFinish = async (values: { name: string }): Promise<void> => {
    try {
      const kb = await mutation.createKnowledgeBase(values.name);
      void message.success('知识库已创建');
      onCreated(kb);
      form.resetFields();
    } catch (err) {
      const apiError = (
        err as { readonly apiError?: { readonly message?: string } } | undefined
      )?.apiError;
      const text = apiError?.message ?? '创建失败';
      void message.error(text);
    }
  };

  return (
    <Modal
      title="新建知识库"
      open={open}
      onCancel={onClose}
      destroyOnHidden
      footer={null}
    >
      <Form<{ name: string }>
        form={form}
        layout="vertical"
        onFinish={(v) => {
          void handleFinish(v);
        }}
        disabled={mutation.isPending}
        aria-label="新建知识库表单"
      >
        <Form.Item
          label="名称"
          name="name"
          rules={[
            { required: true, message: '请输入名称' },
            { max: 128, message: '名称最长 128 字符' },
          ]}
        >
          <Input placeholder="知识库名称" autoFocus maxLength={128} />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" htmlType="submit" loading={mutation.isPending}>
              创建
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Modal>
  );
}

// We must import the hook lazily here only to avoid a circular import cycle
// warning in some bundler configurations; the import is a normal static import
// resolved at module load.

