/**
 * Component tests for the KB-scoped ApiKeysTabView.
 *
 * Proves (with a stubbed ViewModel):
 *  - Active rows render disable/rotate/revoke; DISABLED render enable/revoke;
 *    REVOKED render no actions (the action matrix is wired to the View).
 *  - The create button is disabled when canCreate=false.
 *  - The one-time secret modal opens when viewModel.secret.secret is non-null
 *    and calls clearSecret on close.
 *  - Empty and error states render correctly.
 *
 * Note: the View renders both a desktop table and a mobile structured list
 * (hidden via CSS). Tests use getAllBy* to tolerate the duplicate DOM nodes.
 */
import { describe, it, expect, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { ApiKeysTabView } from './api-keys-tab-view';
import type { ApiKeysViewModel } from '@app/api-keys/use-api-keys';
import type { ApiKeyItem } from '../model/mapper';
import { allowedApiKeyActions } from '../model/actions';

function wrap(ui: ReactElement): ReturnType<typeof render> {
  return render(<App>{ui}</App>);
}

function keyItem(overrides: Partial<ApiKeyItem> = {}): ApiKeyItem {
  return {
    id: '5001',
    knowledgeBaseId: '3001',
    name: 'prod-key',
    keyPrefix: 'crag_abcd',
    status: 'ACTIVE',
    statusForDisplay: 'ACTIVE',
    expiresAt: null,
    ...overrides,
  };
}

function stubViewModel(overrides: Partial<ApiKeysViewModel> = {}): ApiKeysViewModel {
  const noop = () => {
    /* noop */
  };
  return {
    status: 'ready',
    items: [keyItem()],
    errorMessage: null,
    refetch: () => Promise.resolve(),
    create: {
      createKey: () => Promise.reject(new Error('not stubbed')),
      isPending: false,
      error: null,
      reset: noop,
    },
    disable: { run: () => Promise.reject(new Error('not stubbed')), isPending: false, error: null, reset: noop },
    enable: { run: () => Promise.reject(new Error('not stubbed')), isPending: false, error: null, reset: noop },
    rotate: { rotateKey: () => Promise.reject(new Error('not stubbed')), isPending: false, error: null, reset: noop },
    revoke: { run: () => Promise.reject(new Error('not stubbed')), isPending: false, error: null, reset: noop },
    secret: { secret: null, clearSecret: noop },
    allowedActions: allowedApiKeyActions,
    ...overrides,
  };
}

/** Build a regex that tolerates Ant Design's CJK auto-spacing (e.g. "禁 用"). */
function cjk(name: string): RegExp {
  // Allow optional whitespace between every character.
  const chars = name.split('');
  const pattern = chars.map((c) => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s*');
  return new RegExp(pattern);
}

/** Assert at least one button with the given accessible name exists. */
function expectButtonExists(name: string): void {
  const matches = screen.getAllByRole('button', { name: cjk(name) });
  expect(matches.length).toBeGreaterThan(0);
}
function expectButtonAbsent(name: string): void {
  expect(screen.queryAllByRole('button', { name: cjk(name) })).toHaveLength(0);
}

// Ant Design Table renders slowly in jsdom; give these a longer timeout to
// avoid flakes when the full suite runs in parallel.
describe('ApiKeysTabView', () => {
  it('renders an ACTIVE row with disable/rotate/revoke actions', { timeout: 15_000 }, () => {
    wrap(<ApiKeysTabView viewModel={stubViewModel()} canCreate={true} />);
    expectButtonExists('禁用');
    expectButtonExists('轮换');
    expectButtonExists('撤销');
  });

  it('renders a DISABLED row with enable/revoke actions only', { timeout: 15_000 }, () => {
    const vm = stubViewModel({
      items: [keyItem({ status: 'DISABLED', statusForDisplay: 'DISABLED' })],
    });
    wrap(<ApiKeysTabView viewModel={vm} canCreate={true} />);
    expectButtonExists('启用');
    expectButtonExists('撤销');
    expectButtonAbsent('禁用');
    expectButtonAbsent('轮换');
  });

  it('renders a REVOKED row with no action buttons', { timeout: 15_000 }, () => {
    const vm = stubViewModel({
      items: [keyItem({ status: 'REVOKED', statusForDisplay: 'REVOKED' })],
    });
    wrap(<ApiKeysTabView viewModel={vm} canCreate={true} />);
    expectButtonAbsent('禁用');
    expectButtonAbsent('启用');
    expectButtonAbsent('轮换');
    expectButtonAbsent('撤销');
  });

  it('disables the create button when canCreate=false', { timeout: 15_000 }, () => {
    wrap(<ApiKeysTabView viewModel={stubViewModel()} canCreate={false} />);
    const createButton = screen.getByRole('button', { name: /新建/ });
    expect((createButton as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables the create button when canCreate=true', { timeout: 15_000 }, () => {
    wrap(<ApiKeysTabView viewModel={stubViewModel()} canCreate={true} />);
    const createButton = screen.getByRole('button', { name: /新建/ });
    expect((createButton as HTMLButtonElement).disabled).toBe(false);
  });

  it('opens the save-key modal when secret is non-null and clears on close', { timeout: 15_000 }, async () => {
    const clearSecret = vi.fn();
    const vm = stubViewModel({
      secret: {
        secret: {
          ...keyItem({ id: '5002' }),
          completeKey: 'crag_abcd_<PLACEHOLDER_SECRET>',
        },
        clearSecret,
      },
    });
    wrap(<ApiKeysTabView viewModel={vm} canCreate={true} />);
    // The modal title should appear.
    await waitFor(() => expect(screen.getAllByText(/保存你的/).length).toBeGreaterThan(0));
    // Check the checkbox then click Done to trigger close.
    const checkbox = await screen.findByRole('checkbox');
    fireEvent.click(checkbox);
    const doneButton = await screen.findByRole('button', { name: /完\s*成/ });
    await waitFor(() => expect((doneButton as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(doneButton);
    await waitFor(() => expect(clearSecret).toHaveBeenCalled());
  });

  it('renders the empty state when status is empty', () => {
    wrap(<ApiKeysTabView viewModel={stubViewModel({ status: 'empty', items: [] })} canCreate={true} />);
    expect(screen.getByText(/还没有/)).toBeTruthy();
  });

  it('renders the error state when status is error', () => {
    wrap(
      <ApiKeysTabView
        viewModel={stubViewModel({ status: 'error', items: [], errorMessage: '连接超时' })}
        canCreate={true}
      />,
    );
    expect(screen.getByText(/加载失败/)).toBeTruthy();
    expect(screen.getByText('连接超时')).toBeTruthy();
  });
});
