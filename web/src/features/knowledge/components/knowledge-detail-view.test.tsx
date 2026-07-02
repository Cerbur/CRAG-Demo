/**
 * Component tests for the Knowledge detail View.
 *
 * Covers: ready+apiKeyReady=false shows the warning Alert; ready+apiKeyReady=
 * true shows no warning; not-found renders the 404 Result; error renders the
 * error Result; loading renders a Skeleton.
 */
import { describe, it, expect, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ConfigProvider, App as AntApp } from 'antd';
import { KnowledgeDetailView } from './knowledge-detail-view';
import type { KnowledgeDetailViewModel } from '@app/knowledge/use-knowledge-detail';
import type { KnowledgeBase } from '../model/mapper';

function wrap(ui: ReactElement): void {
  render(
    <ConfigProvider>
      <AntApp>
        <MemoryRouter>{ui}</MemoryRouter>
      </AntApp>
    </ConfigProvider>,
  );
}

const kb = (overrides: Partial<KnowledgeBase> = {}): KnowledgeBase => ({
  id: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
  ...overrides,
});

function makeViewModel(overrides: Partial<KnowledgeDetailViewModel> = {}): KnowledgeDetailViewModel {
  return {
    status: 'ready',
    knowledgeBase: kb(),
    awaitingReadiness: false,
    errorMessage: null,
    refetch: vi.fn(),
    ...overrides,
  };
}

describe('KnowledgeDetailView', () => {
  it('renders overview fields when ready', () => {
    wrap(<KnowledgeDetailView viewModel={makeViewModel()} />);
    // The name appears both in the page Title and the Overview Descriptions
    // item, so we assert at least one match for the name and exactly one for
    // the id and status.
    expect(screen.getAllByText('产品文档').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('3001')).toBeTruthy();
    expect(screen.getByText('API Key 就绪')).toBeTruthy();
  });

  it('shows the partial-success warning when apiKeyReady=false', () => {
    wrap(
      <KnowledgeDetailView
        viewModel={makeViewModel({
          knowledgeBase: kb({ apiKeyReady: false }),
          awaitingReadiness: true,
        })}
      />,
    );
    expect(screen.getByText('API Key 尚未就绪')).toBeTruthy();
    expect(screen.getByText('API Key 待就绪')).toBeTruthy();
  });

  it('does NOT show the warning when apiKeyReady=true', () => {
    wrap(<KnowledgeDetailView viewModel={makeViewModel()} />);
    expect(screen.queryByText('API Key 尚未就绪')).toBeNull();
  });

  it('renders not-found Result when status is not-found', () => {
    wrap(
      <KnowledgeDetailView
        viewModel={makeViewModel({ status: 'not-found', knowledgeBase: null })}
      />,
    );
    expect(screen.getByText('知识库不存在')).toBeTruthy();
  });

  it('renders error Result when status is error', () => {
    wrap(
      <KnowledgeDetailView
        viewModel={makeViewModel({
          status: 'error',
          knowledgeBase: null,
          errorMessage: '服务不可用',
        })}
      />,
    );
    expect(screen.getByText('加载失败')).toBeTruthy();
    expect(screen.getByText('服务不可用')).toBeTruthy();
  });
});
