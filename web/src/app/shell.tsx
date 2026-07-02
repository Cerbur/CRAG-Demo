import { useState, type JSX, type ReactNode } from 'react';
import { Layout, Menu, Grid, Drawer, Button, Typography, Space } from 'antd';
import { useLocation, useNavigate, Outlet } from 'react-router';
import {
  BookOutlined,
  KeyOutlined,
  MessageOutlined,
  MenuOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { ROUTE_PATHS } from './routes';

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

function AccountStub(): ReactNode {
  // 22.1 stub. 22.3 wires real logout + session info.
  return (
    <Space size="small">
      <UserOutlined />
      <span>账户</span>
    </Space>
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
 * the account stub.
 *
 * Mobile (<768px): Sider collapses into a hamburger Drawer.
 *
 * Placeholder pages render inside <Outlet/>. No API calls happen in 22.1.
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
    <Layout style={{ minHeight: '100vh' }}>
      {isDesktop ? (
        <Sider breakpoint="md" collapsible={false} width={224} theme="light">
          <div style={{ padding: '16px 20px' }}>
            <Title level={4} style={{ margin: 0 }}>
              CRAG Console
            </Title>
          </div>
          {menu}
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
        <Header
          style={{
            background: '#fff',
            paddingInline: isDesktop ? 24 : 12,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          {isDesktop ? null : (
            <Button
              type="text"
              icon={<MenuOutlined />}
              aria-label="打开导航"
              onClick={() => setDrawerOpen(true)}
            />
          )}
          <div>{AccountStub()}</div>
        </Header>
        <Content style={{ padding: isDesktop ? 24 : 12 }}>
          <Outlet />
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
