import type { JSX, ReactNode } from 'react';
import { Button, Empty, Result, Skeleton } from 'antd';

export type AsyncViewState = 'loading' | 'empty' | 'error' | 'ready';

export interface AsyncStateProps {
  readonly state: AsyncViewState;
  readonly children?: ReactNode;
  readonly emptyDescription?: string;
  readonly errorMessage?: string;
  readonly onRetry?: () => void;
}

export function AsyncState({
  state,
  children,
  emptyDescription = '暂无内容',
  errorMessage = '加载失败',
  onRetry,
}: AsyncStateProps): JSX.Element {
  if (state === 'ready') return <>{children}</>;

  if (state === 'loading') {
    return (
      <section className="async-state" role="status" aria-label="正在加载" aria-live="polite">
        <Skeleton active paragraph={{ rows: 4 }} />
      </section>
    );
  }

  if (state === 'empty') {
    return (
      <section className="async-state" role="status" aria-label="暂无内容">
        <Empty description={emptyDescription} />
      </section>
    );
  }

  return (
    <section className="async-state" role="status" aria-label="加载失败" aria-live="polite">
      <Result
        status="error"
        title={errorMessage}
        extra={onRetry ? <Button onClick={onRetry}>重试</Button> : undefined}
      />
    </section>
  );
}
