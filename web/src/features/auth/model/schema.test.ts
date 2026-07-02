import { describe, it, expect } from 'vitest';
import {
  loginSchema,
  registerFormSchema,
  toLoginRequestBody,
  toRegisterRequestBody,
} from './schema';

describe('loginSchema', () => {
  it('accepts a valid login', () => {
    const r = loginSchema.safeParse({ username: 'alice', password: 'secret123456' });
    expect(r.success).toBe(true);
  });

  it('rejects empty username', () => {
    const r = loginSchema.safeParse({ username: '', password: 'x' });
    expect(r.success).toBe(false);
  });

  it('rejects empty password', () => {
    const r = loginSchema.safeParse({ username: 'a', password: '' });
    expect(r.success).toBe(false);
  });
});

describe('registerFormSchema', () => {
  const valid = {
    nickname: 'alice',
    username: 'alice',
    password: 'password123456',
    confirmPassword: 'password123456',
  };

  it('accepts a valid register form', () => {
    expect(registerFormSchema.safeParse(valid).success).toBe(true);
  });

  it('rejects password shorter than 12 chars', () => {
    const r = registerFormSchema.safeParse({ ...valid, password: 'short', confirmPassword: 'short' });
    expect(r.success).toBe(false);
    if (!r.success) {
      const pwdIssue = r.error.issues.find((i) => i.path[0] === 'password');
      expect(pwdIssue).toBeDefined();
    }
  });

  it('rejects username shorter than 3 chars', () => {
    const r = registerFormSchema.safeParse({ ...valid, username: 'ab' });
    expect(r.success).toBe(false);
  });

  it('rejects nickname longer than 64 chars', () => {
    const r = registerFormSchema.safeParse({ ...valid, nickname: 'x'.repeat(65) });
    expect(r.success).toBe(false);
  });

  it('rejects mismatched confirmPassword with a field error on confirmPassword', () => {
    const r = registerFormSchema.safeParse({ ...valid, confirmPassword: 'different123456' });
    expect(r.success).toBe(false);
    if (!r.success) {
      const confirmIssue = r.error.issues.find((i) => i.path[0] === 'confirmPassword');
      expect(confirmIssue).toBeDefined();
    }
  });
});

describe('request body projection', () => {
  it('toRegisterRequestBody strips confirmPassword', () => {
    const body = toRegisterRequestBody({
      nickname: 'alice',
      username: 'alice',
      password: 'password123456',
      confirmPassword: 'password123456',
    });
    expect(body).toEqual({ nickname: 'alice', username: 'alice', password: 'password123456' });
    expect(body).not.toHaveProperty('confirmPassword');
  });

  it('toLoginRequestBody shapes the wire body', () => {
    expect(toLoginRequestBody({ username: 'alice', password: 'p' })).toEqual({
      username: 'alice',
      password: 'p',
    });
  });
});
