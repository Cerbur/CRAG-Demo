/**
 * Login form (View layer).
 *
 * Pure presentation: takes a ViewModel and renders an Ant Design Form. On
 * submit calls `viewModel.submit`. Reads `status` and `fieldErrors` to drive
 * loading state, field-level errors and form-level error alert. Does NOT
 * import services/http, parse API codes or own business state.
 *
 * The form-level error (keyed `_form` in the ViewModel's fieldErrors) renders
 * as an Ant Design Alert above the form fields.
 */
import { type JSX } from 'react';
import { Form, Input, Button, Alert, Card, Typography, App } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Link } from 'react-router';
import type { AuthViewModel } from '@app/session/use-auth-view-model';
import type { LoginFormValues } from '../model/schema';

const { Title } = Typography;

interface LoginFormProps {
  readonly viewModel: AuthViewModel<LoginFormValues>;
}

export function LoginForm({ viewModel }: LoginFormProps): JSX.Element {
  const { message } = App.useApp();
  const submitting = viewModel.status === 'submitting';
  const formError = viewModel.fieldErrors._form;

  const handleSubmit = async (values: LoginFormValues): Promise<void> => {
    try {
      await viewModel.submit(values);
      if (viewModel.status === 'authenticated') {
        void message.success('登录成功');
      }
    } catch {
      // ViewModel already captured the error; no-op here.
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '16px',
        background: '#f5f5f5',
      }}
    >
      <Card style={{ width: '100%', maxWidth: 400 }}>
        <Title level={3} style={{ marginTop: 0, marginBottom: 24 }}>
          登录 CRAG Console
        </Title>
        {formError ? (
          <Alert
            type="error"
            title={formError}
            showIcon
            style={{ marginBottom: 16 }}
            role="alert"
          />
        ) : null}
        <Form<LoginFormValues>
          layout="vertical"
          onFinish={handleSubmit}
          disabled={submitting}
          aria-label="登录表单"
        >
          <Form.Item
            label="用户名"
            name="username"
            validateStatus={viewModel.fieldErrors.username ? 'error' : ''}
            help={viewModel.fieldErrors.username}
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            validateStatus={viewModel.fieldErrors.password ? 'error' : ''}
            help={viewModel.fieldErrors.password}
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 8 }}>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form.Item>
          <div style={{ textAlign: 'center' }}>
            还没有账号？<Link to="/register">立即注册</Link>
          </div>
        </Form>
      </Card>
    </div>
  );
}
