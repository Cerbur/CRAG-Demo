import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AsyncState } from './async-state';

describe('AsyncState', () => {
  it.each([
    ['loading', '正在加载'],
    ['empty', '暂无内容'],
    ['error', '加载失败'],
  ] as const)('renders a stable accessible %s state', (state, label) => {
    render(<AsyncState state={state} onRetry={vi.fn()} />);
    expect(screen.getByRole('status', { name: label })).toBeTruthy();
  });

  it('renders ready content without an extra status wrapper', () => {
    render(
      <AsyncState state="ready">
        <p>内容已就绪</p>
      </AsyncState>,
    );
    expect(screen.getByText('内容已就绪')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
  });
});
