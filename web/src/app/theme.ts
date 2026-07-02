import type { ThemeConfig } from 'antd';

export const cragTheme: ThemeConfig = {
  token: {
    colorPrimary: '#0958d9',
    colorSuccess: '#237804',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorBgLayout: '#f5f5f5',
    colorBgContainer: '#ffffff',
    colorText: '#262626',
    colorTextSecondary: '#595959',
    colorTextDisabled: '#595959',
    colorBorder: '#d9d9d9',
    borderRadius: 4,
    borderRadiusLG: 8,
    fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize: 14,
  },
  components: {
    Button: { controlHeight: 36 },
    Card: { paddingLG: 20 },
    Layout: { bodyBg: '#f5f5f5', headerBg: '#ffffff', siderBg: '#001529' },
    Menu: { darkItemBg: '#001529', darkItemSelectedBg: '#0958d9' },
    Modal: { borderRadiusLG: 8 },
    Table: { headerBg: '#fafafa', headerColor: '#262626', cellPaddingBlockSM: 8 },
  },
};
