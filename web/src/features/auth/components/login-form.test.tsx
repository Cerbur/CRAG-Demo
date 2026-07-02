import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntApp } from 'antd';
import type { ReactElement } from 'react';
import { LoginForm } from './login-form';
import type { AuthViewModel } from '@app/session/use-auth-view-model';
import type { LoginFormValues } from '../model/schema';

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

function makeViewModel(overrides: Partial<AuthViewModel<LoginFormValues>> = {}): AuthViewModel<LoginFormValues> {
  return {
    status: 'idle',
    fieldErrors: {},
    session: null,
    submit: async () => {},
    reset: () => {},
    ...overrides,
  };
}

/** Ant Design 6 inserts a space between two CJK chars in buttons ("登录" → "登 录"). */
function findButtonByLabel(label: string): HTMLButtonElement {
  const buttons = screen.getAllByRole('button') as HTMLButtonElement[];
  const match = buttons.find((b) => (b.textContent ?? '').replace(/\s+/g, '') === label);
  if (!match) {
    throw new Error(
      `button labeled "${label}" not found; buttons: ${JSON.stringify(buttons.map((b) => b.textContent))}`,
    );
  }
  return match;
}

describe('LoginForm', () => {
  it('renders username/password inputs and submit button', () => {
    wrap(<LoginForm viewModel={makeViewModel()} />);
    expect(screen.getByLabelText('登录表单')).toBeTruthy();
    expect(screen.getByPlaceholderText('用户名')).toBeTruthy();
    expect(screen.getByPlaceholderText('密码')).toBeTruthy();
    expect(findButtonByLabel('登录')).toBeTruthy();
  });

  it('shows a link to the register page', () => {
    wrap(<LoginForm viewModel={makeViewModel()} />);
    const links = screen.getAllByText('立即注册');
    expect(links.length).toBeGreaterThanOrEqual(1);
    expect(links[0]!.getAttribute('href')).toBe('/register');
  });

  it('renders form-level error alert when _form is set', () => {
    wrap(
      <LoginForm
        viewModel={makeViewModel({ status: 'error', fieldErrors: { _form: 'Authentication failed' } })}
      />,
    );
    expect(screen.getByText('Authentication failed')).toBeTruthy();
  });

  it('renders field error help when username has an error', () => {
    wrap(
      <LoginForm
        viewModel={makeViewModel({ status: 'error', fieldErrors: { username: 'taken' } })}
      />,
    );
    expect(screen.getByText('taken')).toBeTruthy();
  });

  it('calls submit with the entered values', async () => {
    const submitted: LoginFormValues[] = [];
    const vm = makeViewModel({
      submit: async (values) => {
        submitted.push(values);
      },
    });
    wrap(<LoginForm viewModel={vm} />);
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('用户名'), 'alice');
    await user.type(screen.getByPlaceholderText('密码'), 'password123456');
    await user.click(findButtonByLabel('登录'));
    await waitFor(() => {
      expect(submitted).toEqual([{ username: 'alice', password: 'password123456' }]);
    });
  });

  it('disables inputs while submitting', () => {
    wrap(<LoginForm viewModel={makeViewModel({ status: 'submitting' })} />);
    expect(findButtonByLabel('登录').disabled).toBe(true);
  });
});
