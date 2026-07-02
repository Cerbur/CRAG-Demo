import { describe, expect, it } from 'vitest';
import { cragTheme } from './theme';

describe('cragTheme', () => {
  it('encodes the approved stitch-v4 visual tokens', () => {
    expect(cragTheme.token).toMatchObject({
      colorPrimary: '#0958d9',
      colorBgLayout: '#f5f5f5',
      colorText: '#262626',
      colorTextSecondary: '#595959',
      borderRadius: 4,
      fontSize: 14,
    });
    expect(cragTheme.components).toMatchObject({
      Layout: { siderBg: '#001529' },
      Table: { headerBg: '#fafafa', cellPaddingBlockSM: 8 },
    });
  });
});
