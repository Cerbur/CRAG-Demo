import type { JSX } from 'react';
import { Result, Button } from 'antd';
import { useNavigate } from 'react-router';
import { ROUTE_PATHS } from '../app/routes';

export function NotFoundPage(): JSX.Element {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title="404"
      subTitle="抱歉，您访问的页面不存在。"
      extra={
        <Button type="primary" onClick={() => navigate(ROUTE_PATHS.login)}>
          返回登录
        </Button>
      }
    />
  );
}
