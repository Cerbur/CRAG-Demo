/**
 * Chat View — independent knowledge retrieval chat.
 *
 * Behavior rules (plan_22 §22.7 acceptance + MANIFEST Chat):
 *  - The API Key is entered inline and held ONLY in page memory (the ViewModel
 *    under app/chat owns the state). Refreshing the page clears it.
 *  - The composer is fixed at the bottom; the last message is never obscured
 *    (scroll container has bottom padding that clears the composer + safe area).
 *  - Sources render as a compact list per assistant message showing ONLY
 *    Reference / Document ID (monospace) / Excerpt. Sources are NOT clickable.
 *  - Failed queries are RETAINED with an explicit Retry button. There is no
 *    automatic retry of LLM Query (OpenAPI non-goal).
 *  - No streaming, no history persistence, no KB/model selector, no attachment,
 *    no "Press Enter to send" teaching copy.
 */
import { type JSX, useEffect, useRef } from 'react';
import { Alert, Button, Empty, Input, Space, Spin, Typography } from 'antd';
import { SendOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ChatViewModel } from '@app/chat/use-chat';
import type { ChatMessage, QuerySource } from '../model/types';

const { Text, Paragraph } = Typography;

interface ChatViewProps {
  readonly viewModel: ChatViewModel;
}

const PLACEHOLDER_KEY = 'crag_<前缀>_<秘密>';

export function ChatView({ viewModel }: ChatViewProps): JSX.Element {
  const { messages, isSending, lastError, apiKey, question, canSubmit } = viewModel;
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when a new message arrives or status changes.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) {
      el.scrollTo({ top: el.scrollHeight });
    }
  }, [messages, isSending]);

  const noKey = apiKey.length === 0;

  return (
    <div className="chat-page">
      {/* API Key entry (inline, masked). Lives only in page memory. */}
      <div className="chat-key-bar">
        <Space.Compact style={{ width: '100%' }}>
          <Input.Password
            value={apiKey}
            onChange={(e) => viewModel.setKey(e.target.value)}
            placeholder={PLACEHOLDER_KEY}
            aria-label="API Key"
            autoComplete="off"
            disabled={isSending}
          />
          <Button
            onClick={() => viewModel.clearKey()}
            disabled={isSending || (noKey && messages.length === 0)}
            aria-label="清除密钥与会话"
          >
            清除
          </Button>
        </Space.Compact>
        <Text type="secondary" className="chat-key-hint">
          密钥仅保存在本页内存，刷新页面即清除。
        </Text>
      </div>

      {/* Transcript. */}
      <div className="chat-transcript" ref={scrollRef}>
        {messages.length === 0 ? (
          <Empty
            description={noKey ? '请输入 API Key 后开始提问' : '输入问题并点击发送开始对话'}
            style={{ marginTop: 48 }}
          />
        ) : (
          <ul className="chat-message-list">
            {messages.map((m) => (
              <MessageRow key={m.id} message={m} viewModel={viewModel} />
            ))}
          </ul>
        )}
      </div>

      {/* Composer fixed at bottom. */}
      <div className="chat-composer">
        {lastError ? <ErrorBanner viewModel={viewModel} /> : null}
        <Space.Compact style={{ width: '100%' }}>
          <Input.TextArea
            value={question}
            onChange={(e) => viewModel.setQuestion(e.target.value)}
            placeholder="输入问题…"
            aria-label="问题"
            autoSize={{ minRows: 1, maxRows: 4 }}
            disabled={noKey || isSending}
            onPressEnter={(e) => {
              // Enter sends (Shift+Enter inserts newline). Disabled while sending
              // or without a key — Input is already disabled in those cases, so
              // this handler only fires when submission is allowed.
              if (!e.shiftKey) {
                e.preventDefault();
                if (canSubmit) {
                  void viewModel.submit();
                }
              }
            }}
          />
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={() => {
              void viewModel.submit();
            }}
            disabled={!canSubmit}
            loading={isSending}
            aria-label="发送"
          >
            发送
          </Button>
        </Space.Compact>
      </div>
    </div>
  );
}

function MessageRow({
  message,
  viewModel,
}: {
  readonly message: ChatMessage;
  readonly viewModel: ChatViewModel;
}): JSX.Element {
  const isUser = message.role === 'user';
  return (
    <li className={`chat-message chat-message-${message.role}`}>
      <div className="chat-bubble">
        {isUser ? (
          <Paragraph style={{ margin: 0 }}>{message.content}</Paragraph>
        ) : message.status === 'sending' ? (
          <Spin size="small" />
        ) : message.status === 'failed' ? (
          <Space orientation="vertical" size={8} style={{ width: '100%' }}>
            <Text type="danger">回答失败，请重试。</Text>
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={() => {
                void viewModel.retry();
              }}
              disabled={viewModel.isSending}
            >
              重试
            </Button>
          </Space>
        ) : (
          <Space orientation="vertical" size={8} style={{ width: '100%' }}>
            <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
              {message.content || '（无答案）'}
            </Paragraph>
            {message.sources.length > 0 ? <SourcesBlock sources={message.sources} /> : null}
          </Space>
        )}
      </div>
    </li>
  );
}

function SourcesBlock({ sources }: { readonly sources: ReadonlyArray<QuerySource> }): JSX.Element {
  return (
    <div className="chat-sources">
      <Text type="secondary" strong>
        来源
      </Text>
      <ul className="chat-sources-list">
        {sources.map((s, i) => (
          <li key={`${s.reference}-${i}`} className="chat-source-item">
            <Space orientation="vertical" size={2} style={{ width: '100%' }}>
              <Space size={8} wrap>
                <Text strong>{s.reference}</Text>
                <Text code style={{ fontSize: 12 }}>
                  {s.documentId}
                </Text>
              </Space>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {s.excerpt}
              </Text>
            </Space>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ErrorBanner({ viewModel }: { readonly viewModel: ChatViewModel }): JSX.Element {
  const err = viewModel.lastError;
  if (!err) return <></>;
  const messageType =
    err.kind === 'authentication'
      ? 'API Key 无效或已失效，请检查后重新输入。'
      : err.kind === 'retryable'
        ? `${err.message}（可重试）`
        : err.kind === 'validation'
          ? '问题格式不正确。'
          : err.kind === 'business'
            ? '未找到对应的资源，可能 Knowledge 已删除。'
            : err.message;
  return (
    <Alert
      type={
        err.kind === 'authentication' ? 'error' : err.kind === 'retryable' ? 'warning' : 'error'
      }
      showIcon
      title={messageType}
      style={{ marginBottom: 8 }}
    />
  );
}
