/**
 * Register form (View layer).
 *
 * Same MVVM contract as {@link ./login-form.tsx}: takes a ViewModel, renders an
 * Ant Design Form, calls `viewModel.submit` on submit. The confirmPassword
 * field is UI-only and stripped by the schema before hitting the wire.
 */
import { type JSX } from 'react';
import { Form, Input, Button, Alert, Card, Typography, App } from 'antd';
import { LockOutlined, UserOutlined, IdcardOutlined } from '@ant-design/icons';
import { Link } from 'react-router';
import type { AuthViewModel } from '@app/session/use-auth-view-model';
import type { RegisterFormValues } from '../model/schema';

const { Title } = Typography;

interface RegisterFormProps {
  readonly viewModel: AuthViewModel<RegisterFormValues>;
}

export function RegisterForm({ viewModel }: RegisterFormProps): JSX.Element {
  const { message } = App.useApp();
  const submitting = viewModel.status === 'submitting';
  const formError = viewModel.fieldErrors._form;

  const handleSubmit = async (values: RegisterFormValues): Promise<void> => {
    try {
      await viewModel.submit(values);
      if (viewModel.status === 'authenticated') {
        void message.success('注册成功');
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
      <Card style={{ width: '100%', maxWidth: 440 }}>
        <Title level={3} style={{ marginTop: 0, marginBottom: 24 }}>
          注册 CRAG Console
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
        <Form<RegisterFormValues>
          layout="vertical"
          onFinish={handleSubmit}
          disabled={submitting}
          aria-label="注册表单"
        >
          <Form.Item
            label="昵称"
            name="nickname"
            validateStatus={viewModel.fieldErrors.nickname ? 'error' : ''}
            help={viewModel.fieldErrors.nickname}
            rules={[{ required: true, message: '请输入昵称' }]}
          >
            <Input prefix={<IdcardOutlined />} placeholder="昵称" autoComplete="nickname" />
          </Form.Item>
          <Form.Item
            label="用户名"
            name="username"
            validateStatus={viewModel.fieldErrors.username ? 'error' : ''}
            help={viewModel.fieldErrors.username}
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="3-32 个字符" autoComplete="username" />
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
              placeholder="至少 12 位"
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item
            label="确认密码"
            name="confirmPassword"
            validateStatus={viewModel.fieldErrors.confirmPassword ? 'error' : ''}
            help={viewModel.fieldErrors.confirmPassword}
            rules={[{ required: true, message: '请再次输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="再次输入密码"
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 8 }}>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              注册
            </Button>
          </Form.Item>
          <div style={{ textAlign: 'center' }}>
            已有账号？<Link to="/login">返回登录</Link>
          </div>
        </Form>
      </Card>
    </div>
  );
}
