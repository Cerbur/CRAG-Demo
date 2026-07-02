/**
 * Zod schemas for the register and login forms.
 *
 * Bounds come from docs/api/console-api.openapi.yaml:
 *  - register: nickname 1–64, username 3–32, password 12–128 (+ UI confirm).
 *  - login: username (non-empty), password (non-empty).
 *
 * The schemas produce the wire-shaped request bodies (no confirm-password
 * field leaks to the API). The confirm-password check lives in a refine layer
 * so the form can map it to the `confirmPassword` field error.
 */
import { z } from 'zod';

export const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
});

/** Raw register form values including the UI-only confirmPassword field. */
export const registerFormSchema = z
  .object({
    nickname: z
      .string()
      .min(1, '昵称不能为空')
      .max(64, '昵称不超过 64 个字符'),
    username: z
      .string()
      .min(3, '用户名至少 3 个字符')
      .max(32, '用户名不超过 32 个字符'),
    password: z
      .string()
      .min(12, '密码至少 12 位')
      .max(128, '密码不超过 128 个字符'),
    confirmPassword: z.string().min(1, '请再次输入密码'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    path: ['confirmPassword'],
    message: '两次输入的密码不一致',
  });

export type LoginFormValues = z.infer<typeof loginSchema>;
export type RegisterFormValues = z.infer<typeof registerFormSchema>;

/** Wire-shaped register request body (confirmPassword stripped). */
export interface RegisterRequestBody {
  readonly nickname: string;
  readonly username: string;
  readonly password: string;
}

/** Wire-shaped login request body. */
export interface LoginRequestBody {
  readonly username: string;
  readonly password: string;
}

/** Project validated register form values to the wire request body. */
export function toRegisterRequestBody(v: RegisterFormValues): RegisterRequestBody {
  return { nickname: v.nickname, username: v.username, password: v.password };
}

/** Project validated login form values to the wire request body. */
export function toLoginRequestBody(v: LoginFormValues): LoginRequestBody {
  return { username: v.username, password: v.password };
}
