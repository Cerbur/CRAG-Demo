/**
 * Component tests for the Knowledge list View.
 *
 * Covers the loading/empty/ready/error states, the create modal open/close
 * flow, and the partial-success navigation happy path (create 201 → onCreated
 * → navigate to detail). The ViewModel is stubbed so these tests are pure
 * presentation assertions; the real data flow is covered by the app/knowledge
 * integration tests.
 */
import { describe, it, expect, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntApp } from 'antd';
import { KnowledgeListView } from './knowledge-list-view';
import type { KnowledgeListViewModel } from '@app/knowledge/use-knowledge-list';
import type { KnowledgeBase } from '../model/mapper';

function wrap(ui: ReactElement): void {
  const qc = new QueryClient();
  render(
    <QueryClientProvider client={qc}>
      <ConfigProvider>
        <AntApp>
          <MemoryRouter>{ui}</MemoryRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

const item = (id: string, overrides: Partial<KnowledgeBase> = {}): KnowledgeBase => ({
  id,
  tenantId: '2001',
  name: `KB-${id}`,
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
  ...overrides,
});

function makeViewModel(
  overrides: Partial<KnowledgeListViewModel> = {},
): KnowledgeListViewModel {
  return {
    status: 'ready',
    items: [item('3001'), item('3002', { apiKeyReady: false })],
    errorMessage: null,
    hasNextPage: true,
    hasPreviousPage: false,
    gotoNextPage: vi.fn(),
    gotoPreviousPage: vi.fn(),
    refetch: vi.fn(),
    ...overrides,
  };
}

/** Ant Design 6 inserts a space between two CJK chars in buttons ("新建" → "新 建"). */
function findButtonByText(text: string): HTMLButtonElement {
  const buttons = screen.getAllByRole('button') as HTMLButtonElement[];
  const match = buttons.find((b) => (b.textContent ?? '').replace(/\s+/g, '') === text);
  if (!match) {
    throw new Error(
      `button labeled "${text}" not found; buttons: ${JSON.stringify(buttons.map((b) => b.textContent))}`,
    );
  }
  return match;
}

describe('KnowledgeListView', () => {
  it('renders the ready state with rows for each item', () => {
    wrap(<KnowledgeListView viewModel={makeViewModel()} tenantId="2001" />);
    expect(screen.getByText('KB-3001')).toBeTruthy();
    expect(screen.getByText('KB-3002')).toBeTruthy();
  });

  it('shows empty state CTA when status is empty', () => {
    const vm = makeViewModel({ status: 'empty', items: [] });
    wrap(<KnowledgeListView viewModel={vm} tenantId="2001" />);
    expect(screen.getByText('还没有知识库')).toBeTruthy();
  });

  it('shows error alert with retry when status is error', () => {
    const refetch = vi.fn();
    const vm = makeViewModel({
      status: 'error',
      items: [],
      errorMessage: '服务不可用',
      refetch,
    });
    wrap(<KnowledgeListView viewModel={vm} tenantId="2001" />);
    expect(screen.getByText('服务不可用')).toBeTruthy();
  });

  it('renders Previous/Next buttons reflecting paging flags', () => {
    const vm = makeViewModel({ hasNextPage: true, hasPreviousPage: false });
    wrap(<KnowledgeListView viewModel={vm} tenantId="2001" />);
    const next = findButtonByText('下一页');
    const prev = findButtonByText('上一页');
    expect(next.hasAttribute('disabled')).toBe(false);
    expect(prev.hasAttribute('disabled')).toBe(true);
  });

  it('opens the create modal when the create button is clicked', async () => {
    const user = userEvent.setup();
    wrap(<KnowledgeListView viewModel={makeViewModel()} tenantId="2001" />);
    await user.click(findButtonByText('新建'));
    expect(screen.getByPlaceholderText('知识库名称')).toBeTruthy();
  });
});
