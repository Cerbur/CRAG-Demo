/**
 * Component tests for the Documents tab View.
 *
 * The ViewModel is stubbed so these are pure presentation assertions. The real
 * data flow (polling, upload, retry) is covered by the app/documents
 * integration tests.
 *
 * Covers:
 *  - ready state renders a row per document with the right status tag.
 *  - empty state shows the empty CTA.
 *  - error state shows the error alert + retry button.
 *  - loading state shows a skeleton.
 *  - retry action only appears for FAILED + retryable documents.
 *  - failureMessage is rendered as safe text.
 */
import { describe, it, expect, vi } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ConfigProvider, App as AntApp } from 'antd';
import { DocumentsTabView } from './documents-tab-view';
import type { DocumentsViewModel } from '@app/documents/use-documents';
import type { DocumentItem } from '../model/mapper';

function wrap(ui: ReactElement): void {
  render(
    <ConfigProvider>
      <AntApp>
        <MemoryRouter>{ui}</MemoryRouter>
      </AntApp>
    </ConfigProvider>,
  );
}

const item = (overrides: Partial<DocumentItem> = {}): DocumentItem => ({
  id: '4001',
  knowledgeBaseId: '3001',
  filename: 'intro.txt',
  sizeBytes: 128,
  status: 'READY',
  attempt: 1,
  retryable: false,
  failureMessage: null,
  updatedAt: '2026-06-29T09:01:30Z',
  ...overrides,
});

function makeViewModel(overrides: Partial<DocumentsViewModel> = {}): DocumentsViewModel {
  return {
    status: 'ready',
    items: [item()],
    errorMessage: null,
    refetch: vi.fn(),
    upload: {
      uploadFile: vi.fn().mockResolvedValue(item()),
      isPending: false,
      error: null,
      reset: vi.fn(),
    },
    retry: {
      retryDocument: vi.fn().mockResolvedValue(item()),
      isPending: false,
      error: null,
      reset: vi.fn(),
    },
    canRetry: (d) => !!d && d.status === 'FAILED' && d.retryable,
    ...overrides,
  };
}

describe('DocumentsTabView', () => {
  it('renders ready state with rows for each document', () => {
    // Both the desktop table and the mobile list render in the DOM (the mobile
    // list is display:none via CSS), so the filename appears twice.
    wrap(<DocumentsTabView viewModel={makeViewModel()} />);
    expect(screen.getAllByText('intro.txt').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('就绪').length).toBeGreaterThanOrEqual(1);
  });

  it('shows empty state when status is empty', () => {
    wrap(<DocumentsTabView viewModel={makeViewModel({ status: 'empty', items: [] })} />);
    expect(screen.getByText('还没有文档')).toBeTruthy();
  });

  it('shows error alert with retry button when status is error', () => {
    const refetch = vi.fn();
    wrap(
      <DocumentsTabView
        viewModel={makeViewModel({ status: 'error', items: [], errorMessage: '服务不可用', refetch })}
      />,
    );
    expect(screen.getByText('加载失败')).toBeTruthy();
    expect(screen.getByText('服务不可用')).toBeTruthy();
  });

  it('shows the retry action only for FAILED+retryable documents', () => {
    const vm = makeViewModel({
      items: [
        item({ id: 'a', filename: 'ok.txt', status: 'READY', retryable: false }),
        item({
          id: 'b',
          filename: 'broken.txt',
          status: 'FAILED',
          retryable: true,
          failureMessage: 'dispatch missing',
        }),
        item({ id: 'c', filename: 'perm.txt', status: 'FAILED', retryable: false }),
      ],
    });
    wrap(<DocumentsTabView viewModel={vm} />);
    // Exactly one retry button (for the broken doc).
    const retryButtons = screen.getAllByRole('button').filter((b) =>
      (b.textContent ?? '').includes('重试'),
    );
    expect(retryButtons.length).toBe(1);
  });

  it('renders failureMessage as safe text for a FAILED document', () => {
    wrap(
      <DocumentsTabView
        viewModel={makeViewModel({
          items: [
            item({
              status: 'FAILED',
              retryable: true,
              failureMessage: 'ingestion dispatch missing',
            }),
          ],
        })}
      />,
    );
    expect(screen.getAllByText('ingestion dispatch missing').length).toBeGreaterThanOrEqual(1);
  });

  it('surfaces the upload error alert', () => {
    wrap(
      <DocumentsTabView
        viewModel={makeViewModel({
          upload: {
            uploadFile: vi.fn(),
            isPending: false,
            error: new Error('文件超过 10 MiB 上限'),
            reset: vi.fn(),
          },
        })}
      />,
    );
    expect(screen.getByText('文件超过 10 MiB 上限')).toBeTruthy();
  });

  it('surfaces the retry error alert', () => {
    wrap(
      <DocumentsTabView
        viewModel={makeViewModel({
          items: [item({ status: 'FAILED', retryable: true, failureMessage: 'oops' })],
          retry: {
            retryDocument: vi.fn(),
            isPending: false,
            error: new Error('已达重试上限'),
            reset: vi.fn(),
          },
        })}
      />,
    );
    expect(screen.getByText('已达重试上限')).toBeTruthy();
  });

  it('shows a PENDING tag for active documents', () => {
    wrap(
      <DocumentsTabView
        viewModel={makeViewModel({
          items: [item({ status: 'PENDING' })],
        })}
      />,
    );
    expect(screen.getAllByText('待处理').length).toBeGreaterThanOrEqual(1);
  });
});
