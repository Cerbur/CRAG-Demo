/**
 * API Keys tab View (KB-scoped).
 *
 * Pure presentation: renders the create button, the key table (desktop) /
 * structured list (mobile), status tags, per-row actions gated by the
 * {@link allowedApiKeyActions} matrix, danger confirmations for revoke/rotate,
 * and the one-time-secret modal.
 *
 * The ViewModel is supplied by the parent (knowledge-detail page) via
 * {@link ApiKeysViewModel} from app/api-keys.
 *
 * Status colour mapping:
 *  - ACTIVE → green (success)
 *  - DISABLED → orange (warning)
 *  - REVOKED / EXPIRED → red (default/error)
 *
 * Security: the completeKey from create/rotate lives ONLY in
 * `viewModel.secret.secret`; this View opens the {@link SaveKeyModal} when it
 * is non-null and calls `viewModel.secret.clearSecret()` on close.
 */
import { type JSX, useState, useCallback } from 'react';
import {
  Alert,
  Button,
  Empty,
  Form,
  Grid,
  Input,
  InputNumber,
  Modal,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
  App,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { ApiKeysViewModel } from '@app/api-keys/use-api-keys';
import type { ApiKeyItem, ApiKeyDisplayStatus } from '../model/mapper';
import type { ApiKeyAction } from '../model/actions';
import { SaveKeyModal } from './save-key-modal';

const { Text } = Typography;

interface ApiKeysTabViewProps {
  readonly viewModel: ApiKeysViewModel;
  /** When false, the create button is disabled (e.g. KB not yet ready). */
  readonly canCreate: boolean;
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

/** Human-readable label for an action button. */
function actionLabel(action: ApiKeyAction): string {
  switch (action) {
    case 'disable':
      return '禁用';
    case 'enable':
      return '启用';
    case 'rotate':
      return '轮换';
    case 'revoke':
      return '撤销';
  }
}

/** Whether an action needs a danger confirmation modal. */
function isDangerous(action: ApiKeyAction): boolean {
  return action === 'revoke' || action === 'rotate';
}

const { useBreakpoint } = Grid;

export function ApiKeysTabView({ viewModel, canCreate }: ApiKeysTabViewProps): JSX.Element {
  const { modal } = App.useApp();
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<{ name: string; ttlSeconds?: number }>();
  // Responsive layout: render the table on md+ screens, the structured list on
  // small screens. Conditional rendering (rather than CSS display:none) avoids
  // duplicate DOM nodes that confuse assistive tech and E2E text locators.
  const screens = useBreakpoint();
  const isDesktop = screens.md === true;

  const runAction = useCallback(
    (action: ApiKeyAction, item: ApiKeyItem): void => {
      const exec = (): Promise<unknown> => {
        switch (action) {
          case 'disable':
            return viewModel.disable.run(item.id);
          case 'enable':
            return viewModel.enable.run(item.id);
          case 'rotate':
            return viewModel.rotate.rotateKey(item.id);
          case 'revoke':
            return viewModel.revoke.run(item.id);
        }
      };
      if (isDangerous(action)) {
        modal.confirm({
          title: `确认${actionLabel(action)} API Key`,
          content: (
            <div>
              <div>
                名称：<Text strong>{item.name}</Text>
              </div>
              <div>
                前缀：<Text code>{item.keyPrefix}</Text>
              </div>
              {action === 'revoke' ? (
                <div style={{ marginTop: 8 }}>撤销后该 Key 将立即失效，且无法恢复。</div>
              ) : (
                <div style={{ marginTop: 8 }}>
                  轮换后旧 Key 将立即失效，并生成新的完整密钥（仅显示一次）。
                </div>
              )}
            </div>
          ),
          okType: 'danger',
          okText: actionLabel(action),
          cancelText: '取消',
          onOk: () =>
            exec().catch(() => {
              /* error surfaced via viewModel.*.error */
            }),
        });
      } else {
        void exec().catch(() => {
          /* error surfaced via viewModel.*.error */
        });
      }
    },
    [viewModel, modal],
  );

  const columns: ColumnsType<ApiKeyItem> = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      width: 200,
      render: (name: string) => <Text ellipsis={{ tooltip: name }}>{name}</Text>,
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
    {
      title: '操作',
      key: 'actions',
      width: 260,
      render: (_: unknown, record: ApiKeyItem) => {
        const actions = viewModel.allowedActions(record.status);
        if (actions.length === 0) return <Text type="secondary">—</Text>;
        return (
          <Space size={4} wrap>
            {actions.map((action) => (
              <Button
                key={action}
                size="small"
                danger={action === 'revoke'}
                onClick={() => runAction(action, record)}
              >
                {actionLabel(action)}
              </Button>
            ))}
          </Space>
        );
      },
    },
  ];

  const handleCreate = async (values: { name: string; ttlSeconds?: number }): Promise<void> => {
    try {
      await viewModel.create.createKey(values.name.trim(), values.ttlSeconds);
      setCreateOpen(false);
      form.resetFields();
    } catch {
      // error surfaced via viewModel.create.error
    }
  };

  return (
    <div>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Text type="secondary">管理该知识库的 API Key 全生命周期。</Text>
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
            disabled={!canCreate}
            onClick={() => setCreateOpen(true)}
            aria-label="新建 API Key"
          >
            新建
          </Button>
        </Space>
      </Space>

      {viewModel.create.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={
            viewModel.create.error instanceof Error ? viewModel.create.error.message : '创建失败'
          }
          closable
          onClose={() => viewModel.create.reset()}
        />
      ) : null}

      {[viewModel.disable, viewModel.enable, viewModel.rotate, viewModel.revoke].some(
        (m) => m.error,
      ) && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title="操作失败"
          description="请检查 Key 状态或权限后重试。"
          closable
          onClose={() => {
            viewModel.disable.reset();
            viewModel.enable.reset();
            viewModel.rotate.reset();
            viewModel.revoke.reset();
          }}
        />
      )}

      {viewModel.status === 'loading' ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : viewModel.status === 'error' ? (
        <Alert
          type="error"
          showIcon
          title="加载失败"
          description={viewModel.errorMessage ?? '请稍后重试'}
          action={
            <Button icon={<ReloadOutlined />} onClick={() => void viewModel.refetch()}>
              重试
            </Button>
          }
        />
      ) : viewModel.status === 'empty' ? (
        <Empty description="还没有 API Key" />
      ) : isDesktop ? (
        <Table<ApiKeyItem>
          rowKey="id"
          columns={columns}
          dataSource={[...viewModel.items]}
          pagination={false}
          size="small"
        />
      ) : (
        <ul style={{ padding: 0, listStyle: 'none' }}>
          {viewModel.items.map((item) => {
            const actions = viewModel.allowedActions(item.status);
            return (
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
                  <Text code style={{ fontSize: 12 }}>
                    {item.keyPrefix}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    过期：{formatDate(item.expiresAt)}
                  </Text>
                  {actions.length > 0 ? (
                    <Space size={4} wrap>
                      {actions.map((action) => (
                        <Button
                          key={action}
                          size="small"
                          danger={action === 'revoke'}
                          onClick={() => runAction(action, item)}
                        >
                          {actionLabel(action)}
                        </Button>
                      ))}
                    </Space>
                  ) : (
                    <Text type="secondary">—</Text>
                  )}
                </Space>
              </li>
            );
          })}
        </ul>
      )}

      <Modal
        title="新建 API Key"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
        footer={null}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(v) => {
            void handleCreate(v);
          }}
          disabled={viewModel.create.isPending}
          aria-label="新建 API Key 表单"
        >
          <Form.Item
            label="名称"
            name="name"
            rules={[
              { required: true, message: '请输入名称' },
              { max: 64, message: '名称最长 64 字符' },
            ]}
          >
            <Input placeholder="例如 prod-key" autoFocus maxLength={64} />
          </Form.Item>
          <Form.Item
            label="有效期（秒，可选）"
            name="ttlSeconds"
            rules={[
              {
                validator: (_: unknown, value: number | undefined) => {
                  if (value === undefined || value === null) return Promise.resolve();
                  if (!Number.isFinite(value) || Math.floor(value) !== value) {
                    return Promise.reject(new Error('必须是整数'));
                  }
                  if (value < 0 || value > 31_536_000) {
                    return Promise.reject(new Error('范围 0..31536000'));
                  }
                  return Promise.resolve();
                },
              },
            ]}
          >
            <InputNumber
              placeholder="留空使用默认（90 天）"
              min={0}
              max={31536000}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setCreateOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={viewModel.create.isPending}>
                创建
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <SaveKeyModal
        open={viewModel.secret.secret !== null}
        completeKey={viewModel.secret.secret?.completeKey ?? null}
        onClose={() => viewModel.secret.clearSecret()}
      />
    </div>
  );
}
