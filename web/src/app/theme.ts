import type { ThemeConfig } from 'antd';

/**
 * CRAG Web Console theme. Tokens are intentionally minimal for 22.1;
 * 22.8 applies the approved Stitch design system.
 *
 * Status color convention (ui-style.md):
 * processing=blue, ready=green, warning=orange, failure/danger=red, disabled=grey.
 */
export const cragTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    borderRadius: 6,
  },
  components: {},
};
