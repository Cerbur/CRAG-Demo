/**
 * "Save your API Key" one-time-secret modal.
 *
 * Security rules (plan_22 §22.6 acceptance):
 *  - The `completeKey` is shown MASKED by default (••••••••). A reveal toggle
 *    (eye icon) shows/hides it.
 *  - A copy button copies the key to the clipboard via `navigator.clipboard`.
 *  - A one-time warning tells the user this is the only chance to save it.
 *  - A "我已保存该密钥" checkbox must be checked before the Done button is
 *    enabled.
 *  - On close (Done or dismiss), the parent MUST call `onClose` so the
 *    ViewModel's `secret.clearSecret()` purges the completeKey from React
 *    state. This component itself does not retain the key after unmount.
 *
 * The completeKey is passed in as a prop; this component never reads it from
 * any cache or storage. The inner body is remounted via a `key` bound to the
 * completeKey so the reveal/confirm state always starts fresh without needing
 * setState-in-effect.
 */
import { type JSX, useState } from 'react';
import { Modal, Button, Input, Checkbox, Typography, Space, App, Alert } from 'antd';
import { CopyOutlined, EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons';

const { Text, Paragraph } = Typography;

interface SaveKeyModalProps {
  /** True when the modal should be open. */
  readonly open: boolean;
  /** The one-time complete key to display. Null when nothing to show. */
  readonly completeKey: string | null;
  /** Called when the user closes the modal (Done or X/dismiss). Must purge. */
  readonly onClose: () => void;
}

function SaveKeyModalBody({
  completeKey,
  onClose,
}: {
  readonly completeKey: string;
  readonly onClose: () => void;
}): JSX.Element {
  const [revealed, setRevealed] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const { message } = App.useApp();

  const handleCopy = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(completeKey);
      void message.success('已复制到剪贴板');
    } catch {
      void message.error('复制失败，请手动选择并复制');
    }
  };

  const handleClose = (): void => {
    setRevealed(false);
    setConfirmed(false);
    onClose();
  };

  return (
    <>
      <Alert
        type="warning"
        showIcon
        message="这是你唯一一次看到完整密钥"
        description="关闭后将无法再次查看。请现在复制并妥善保存。"
        style={{ marginBottom: 16 }}
      />
      <Space.Compact style={{ width: '100%' }}>
        <Input
          value={completeKey}
          readOnly
          type={revealed ? 'text' : 'password'}
          aria-label="完整 API Key"
          addonAfter={
            <Button
              type="text"
              size="small"
              icon={revealed ? <EyeInvisibleOutlined /> : <EyeOutlined />}
              onClick={() => setRevealed((v) => !v)}
              aria-label={revealed ? '隐藏密钥' : '显示密钥'}
            />
          }
        />
        <Button icon={<CopyOutlined />} onClick={() => void handleCopy()} aria-label="复制密钥">
          复制
        </Button>
      </Space.Compact>
      <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 8 }}>
        默认以掩码显示，点击眼睛图标可临时查看。
      </Paragraph>
      <Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)}>
        我已保存该密钥
      </Checkbox>
      <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        勾选后才能点击“完成”并关闭。
      </Text>
      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Button type="primary" onClick={handleClose} disabled={!confirmed}>
          完成
        </Button>
      </div>
    </>
  );
}

export function SaveKeyModal({ open, completeKey, onClose }: SaveKeyModalProps): JSX.Element {
  return (
    <Modal
      title="保存你的 API Key"
      open={open}
      onCancel={onClose}
      destroyOnHidden
      maskClosable={false}
      footer={null}
    >
      {completeKey ? (
        <SaveKeyModalBody key={completeKey} completeKey={completeKey} onClose={onClose} />
      ) : null}
    </Modal>
  );
}
