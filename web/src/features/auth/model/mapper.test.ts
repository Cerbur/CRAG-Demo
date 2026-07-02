import { describe, it, expect } from 'vitest';
import {
  AuthDtoError,
  buildSessionFromLogin,
  buildSessionFromMe,
  buildSessionFromRegister,
} from './mapper';

const registerResult = {
  accessToken: '<PLACEHOLDER_ACCESS_JWT>',
  accessExpiresAt: '2026-07-02T12:00:00Z',
  user: { userId: '1001', nickname: 'alice' },
  defaultTenant: { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
};

const loginResult = {
  accessToken: '<PLACEHOLDER_ACCESS_JWT>',
  accessExpiresAt: '2026-07-02T12:00:00Z',
  user: { userId: '1001', nickname: 'alice' },
  defaultTenant: null,
};

const tenantsResult = {
  items: [
    { tenantId: '2001', name: 'alice 的默认租户', role: 'OWNER' },
    { tenantId: '2002', name: 'other', role: 'MEMBER' },
  ],
  nextPageToken: '',
};

const meResult = { userId: '1001', nickname: 'alice' };

describe('auth mapper', () => {
  describe('buildSessionFromRegister', () => {
    it('uses the defaultTenant directly', () => {
      const r = buildSessionFromRegister(registerResult);
      expect(r.session).toEqual({
        userId: '1001',
        nickname: 'alice',
        tenantId: '2001',
        role: 'OWNER',
      });
      expect(r.accessToken).toBe('<PLACEHOLDER_ACCESS_JWT>');
      expect(r.accessExpiresAt).toBe('2026-07-02T12:00:00Z');
    });

    it('throws when defaultTenant is null (contract violation)', () => {
      expect(() => buildSessionFromRegister(loginResult)).toThrow(AuthDtoError);
    });

    it('throws on missing accessToken', () => {
      expect(() =>
        buildSessionFromRegister({ ...registerResult, accessToken: '' }),
      ).toThrow(AuthDtoError);
    });

    it('throws on invalid role', () => {
      expect(() =>
        buildSessionFromRegister({
          ...registerResult,
          defaultTenant: { tenantId: '2001', name: 't', role: 'SUPER' },
        }),
      ).toThrow(AuthDtoError);
    });
  });

  describe('buildSessionFromLogin', () => {
    it('recovers tenant from the tenants list (first item)', () => {
      const r = buildSessionFromLogin(loginResult, tenantsResult);
      expect(r.session.tenantId).toBe('2001');
      expect(r.session.role).toBe('OWNER');
      expect(r.session.nickname).toBe('alice');
    });

    it('throws when tenant list is empty', () => {
      expect(() =>
        buildSessionFromLogin(loginResult, { items: [], nextPageToken: '' }),
      ).toThrow(AuthDtoError);
    });

    it('throws when result is not an object', () => {
      expect(() => buildSessionFromLogin(null, tenantsResult)).toThrow(AuthDtoError);
      expect(() => buildSessionFromLogin(loginResult, null)).toThrow(AuthDtoError);
    });
  });

  describe('buildSessionFromMe', () => {
    it('combines /me and /tenants', () => {
      const s = buildSessionFromMe(meResult, tenantsResult);
      expect(s).toEqual({
        userId: '1001',
        nickname: 'alice',
        tenantId: '2001',
        role: 'OWNER',
      });
    });

    it('throws when me is malformed', () => {
      expect(() => buildSessionFromMe({ userId: '1' }, tenantsResult)).toThrow(AuthDtoError);
    });
  });
});
