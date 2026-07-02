import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntApp } from 'antd';
import type { ReactElement } from 'react';
import { RegisterForm } from './register-form';
import type { AuthViewModel } from '@app/session/use-auth-view-model';
import type { RegisterFormValues } from '../model/schema';

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

function makeViewModel(
  overrides: Partial<AuthViewModel<RegisterFormValues>> = {},
): AuthViewModel<RegisterFormValues> {
  return {
    status: 'idle',
    fieldErrors: {},
    session: null,
    submit: async () => {},
    reset: () => {},
    ...overrides,
  };
}

/** Ant Design 6 inserts a space between two CJK chars in buttons. */
function findButtonByLabel(label: string): HTMLButtonElement {
  const buttons = screen.getAllByRole('button') as HTMLButtonElement[];
  const match = buttons.find((b) => (b.textContent ?? '').replace(/\s+/g, '') === label);
  if (!match) {
    throw new Error(`button labeled "${label}" not found`);
  }
  return match;
}

describe('RegisterForm', () => {
  it('renders nickname/username/password/confirm inputs and submit button', () => {
    wrap(<RegisterForm viewModel={makeViewModel()} />);
    expect(screen.getByLabelText('注册表单')).toBeTruthy();
    expect(screen.getByPlaceholderText('昵称')).toBeTruthy();
    expect(screen.getByPlaceholderText('3-32 个字符')).toBeTruthy();
    expect(screen.getByPlaceholderText('至少 12 位')).toBeTruthy();
    expect(findButtonByLabel('注册')).toBeTruthy();
  });

  it('shows a link to the login page', () => {
    wrap(<RegisterForm viewModel={makeViewModel()} />);
    const links = screen.getAllByText('返回登录');
    expect(links.length).toBeGreaterThanOrEqual(1);
    expect(links[0]!.getAttribute('href')).toBe('/login');
  });

  it('renders confirmPassword field error from the ViewModel', () => {
    wrap(
      <RegisterForm
        viewModel={makeViewModel({
          status: 'error',
          fieldErrors: { confirmPassword: '两次输入的密码不一致' },
        })}
      />,
    );
    expect(screen.getByText('两次输入的密码不一致')).toBeTruthy();
  });

  it('calls submit with the entered values including confirmPassword', async () => {
    const submitted: RegisterFormValues[] = [];
    const vm = makeViewModel({
      submit: async (values) => {
        submitted.push(values);
      },
    });
    wrap(<RegisterForm viewModel={vm} />);
    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('昵称'), 'alice');
    await user.type(screen.getByPlaceholderText('3-32 个字符'), 'alice');
    await user.type(screen.getByPlaceholderText('至少 12 位'), 'password123456');
    await user.type(screen.getAllByPlaceholderText('再次输入密码')[0]!, 'password123456');
    await user.click(findButtonByLabel('注册'));
    await waitFor(() => {
      expect(submitted).toEqual([
        {
          nickname: 'alice',
          username: 'alice',
          password: 'password123456',
          confirmPassword: 'password123456',
        },
      ]);
    });
  });
});
