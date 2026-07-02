import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Result, Button } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

interface Props {
  children: ReactNode;
}
interface State {
  hasError: boolean;
  message: string;
}

/**
 * Top-level error boundary. Renders a friendly Ant Design result on uncaught
 * errors so the SPA never shows a blank screen. Business ViewModels surface
 * expected failures through ApiError and never throw into this boundary.
 */
export class ErrorBoundary extends Component<Props, State> {
  override state: State = { hasError: false, message: '' };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error.message };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Intentionally minimal: never log token/key payloads here.
    console.error('Unhandled UI error', { name: error.name, info: info.componentStack });
  }

  private handleReload = (): void => {
    this.setState({ hasError: false, message: '' });
    if (typeof window !== 'undefined') {
      window.location.reload();
    }
  };

  override render(): ReactNode {
    if (this.state.hasError) {
      return (
        <Result
          status="500"
          title="页面出现错误"
          subTitle="发生未预期错误，请刷新页面后重试。"
          extra={
            <Button type="primary" icon={<ReloadOutlined />} onClick={this.handleReload}>
              刷新页面
            </Button>
          }
        />
      );
    }
    return this.props.children;
  }
}
