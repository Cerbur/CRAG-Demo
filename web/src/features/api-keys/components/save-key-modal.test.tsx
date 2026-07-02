/**
 * Component tests for the one-time-secret SaveKeyModal.
 *
 * Proves:
 *  - The completeKey is masked by default (type=password).
 *  - The copy button invokes navigator.clipboard.writeText with the key.
 *  - The Done button is disabled until the checkbox is checked.
 *  - Closing the modal calls onClose (which the parent wires to clearSecret).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { type ReactElement } from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { SaveKeyModal } from './save-key-modal';

const COMPLETE_KEY = 'crag_abcd_<PLACEHOLDER_SECRET>';

function wrap(ui: ReactElement): ReturnType<typeof render> {
  return render(<App>{ui}</App>);
}

describe('SaveKeyModal', () => {
  beforeEach(() => {
    vi.stubGlobal('navigator', {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('does not render the body when completeKey is null', () => {
    wrap(<SaveKeyModal open={false} completeKey={null} onClose={() => {}} />);
    expect(screen.queryByText('保存你的 API Key')).toBeNull();
  });

  it('masks the key by default (password input)', async () => {
    wrap(<SaveKeyModal open={true} completeKey={COMPLETE_KEY} onClose={() => {}} />);
    const input = await screen.findByLabelText('完整 API Key');
    expect((input as HTMLInputElement).type).toBe('password');
    expect((input as HTMLInputElement).value).toBe(COMPLETE_KEY);
  });

  it('Done button is disabled until the checkbox is checked', async () => {
    wrap(<SaveKeyModal open={true} completeKey={COMPLETE_KEY} onClose={() => {}} />);
    const doneButton = await screen.findByRole('button', { name: /完\s*成/ });
    expect((doneButton as HTMLButtonElement).disabled).toBe(true);
    const checkbox = await screen.findByRole('checkbox');
    fireEvent.click(checkbox);
    await waitFor(() => expect((doneButton as HTMLButtonElement).disabled).toBe(false));
  });

  it('copy button invokes navigator.clipboard.writeText with the complete key', async () => {
    wrap(<SaveKeyModal open={true} completeKey={COMPLETE_KEY} onClose={() => {}} />);
    const copyButton = await screen.findByRole('button', { name: /复\s*制/ });
    fireEvent.click(copyButton);
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(COMPLETE_KEY));
  });

  it('Done button calls onClose', async () => {
    const onClose = vi.fn();
    wrap(<SaveKeyModal open={true} completeKey={COMPLETE_KEY} onClose={onClose} />);
    const checkbox = await screen.findByRole('checkbox');
    fireEvent.click(checkbox);
    const doneButton = await screen.findByRole('button', { name: /完\s*成/ });
    await waitFor(() => expect((doneButton as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(doneButton);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
