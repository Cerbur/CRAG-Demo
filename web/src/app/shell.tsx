import { useState, type JSX, type ReactNode } from 'react';
import { Layout, Menu, Grid, Drawer, Button, Typography, Space, Dropdown, App } from 'antd';
import { useLocation, useNavigate, Outlet } from 'react-router';
import {
  BookOutlined,
  KeyOutlined,
  MessageOutlined,
  MenuOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { ROUTE_PATHS } from './routes';
import { useSessionContext } from './session/session-context';
import { useLogout } from './session/use-logout';

const { Header, Sider, Content } = Layout;
const { useBreakpoint } = Grid;
const { Title } = Typography;

interface NavItem {
  key: string;
  label: string;
  icon: ReactNode;
}

const PRIMARY_NAV: readonly NavItem[] = [
  { key: ROUTE_PATHS.knowledgeList, label: '知识库', icon: <BookOutlined /> },
  { key: ROUTE_PATHS.apiKeys, label: 'API Keys', icon: <KeyOutlined /> },
  { key: ROUTE_PATHS.chat, label: 'Chat', icon: <MessageOutlined /> },
];

/**
 * Account menu rendered in the shell header. Reads the current session nickname
 * and exposes a logout item. Uses Ant Design App.useApp() message (NOT the
 * static message API per task 22.3) and the Dropdown component for the menu.
 */
function AccountMenu(): ReactNode {
  const { session, status } = useSessionContext();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const logout = useLogout({
    onLoggedOut: () => {
      void message.success('已退出登录');
      navigate(ROUTE_PATHS.login, { replace: true });
    },
  });

  if (status !== 'authenticated' || !session) {
    return (
      <Space size="small">
        <UserOutlined />
        <span>账户</span>
      </Space>
    );
  }

  const handleLogout = (): void => {
    void logout();
  };

  const items = [
    {
      key: 'nickname',
      label: session.nickname,
      disabled: true,
    },
    { type: 'divider' as const },
    {
      key: 'logout',
      label: '退出登录',
      icon: <LogoutOutlined />,
      onClick: handleLogout,
    },
  ];

  return (
    <Dropdown menu={{ items }} placement="bottomRight" trigger={['click']}>
      <Button type="text" aria-label="账户菜单">
        <Space size="small">
          <UserOutlined />
          <span>{session.nickname}</span>
        </Space>
      </Button>
    </Dropdown>
  );
}

function navItems() {
  return PRIMARY_NAV.map((item) => ({
    key: item.key,
    icon: item.icon,
    label: item.label,
  }));
}

/**
 * Desktop: fixed left Sider (Knowledge / API Keys / Chat) + top toolbar with
 * the account menu.
 *
 * Mobile (<768px): Sider collapses into a hamburger Drawer.
 *
 * Authenticated pages render inside <Outlet/>. The /app/* routes are wrapped
 * by ProtectedRoute at the router level; the shell itself just renders layout.
 */
export function AppShell(): JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const screens = useBreakpoint();
  const isDesktop = screens.md === true; // md breakpoint = 768px
  const [drawerOpen, setDrawerOpen] = useState(false);

  const activeKey = resolveActiveKey(location.pathname);

  const menu = (
    <Menu
      mode="inline"
      theme="dark"
      selectedKeys={activeKey ? [activeKey] : []}
      items={navItems()}
      onClick={({ key }) => {
        setDrawerOpen(false);
        navigate(key);
      }}
      style={{ borderInlineEnd: 'none' }}
    />
  );

  return (
    <Layout className="app-shell">
      {isDesktop ? (
        <Sider breakpoint="md" collapsible={false} width={200} theme="dark">
          <div className="app-brand">
            <Title level={4} className="app-brand-title">
              CRAG Console
            </Title>
          </div>
          <div className="app-navigation">{menu}</div>
        </Sider>
      ) : (
        <Drawer
          placement="left"
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          size={240}
          title="CRAG Console"
        >
          {menu}
        </Drawer>
      )}

      <Layout>
        <Header className="app-header" style={{ paddingInline: isDesktop ? 24 : 12 }}>
          {isDesktop ? null : (
            <Button
              type="text"
              icon={<MenuOutlined />}
              aria-label="打开导航"
              onClick={() => setDrawerOpen(true)}
            />
          )}
          <div>{AccountMenu()}</div>
        </Header>
        <Content className="app-content" style={{ padding: isDesktop ? 24 : 12 }}>
          <div className="app-content-inner">
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}

function resolveActiveKey(pathname: string): string | undefined {
  if (pathname.startsWith(ROUTE_PATHS.knowledgeList)) {
    return ROUTE_PATHS.knowledgeList;
  }
  if (pathname.startsWith(ROUTE_PATHS.apiKeys)) {
    return ROUTE_PATHS.apiKeys;
  }
  if (pathname.startsWith(ROUTE_PATHS.chat)) {
    return ROUTE_PATHS.chat;
  }
  return undefined;
}
