/**
 * Login and Register ViewModels.
 *
 * These hooks live under app/session (NOT features/auth) because they orchestrate
 * the Console client and SessionStore. Pages call `submit`, read `status` and
 * `fieldErrors`, and render accordingly. The hooks never leak the raw JWT or
 * password to logs/UI.
 *
 * Error mapping (web/constraints/api-client.md §4 + task 22.3):
 *  - 400 validation → fieldErrors keyed by form field name.
 *  - 401 invalid credentials → form-level error (generic "Authentication
 *    failed"); the server intentionally returns a generic message so we do NOT
 *    reveal which field was wrong.
 *  - 403 / 503 / retryable → form-level error with retryable flag.
 *  - network / unknown → form-level generic error.
 */
import { useCallback, useState } from 'react';
import { ApiErrorException, type ApiError } from '@services/http/api-error';
import type { AuthSession } from '@entities/session';
import {
  loginWithCredentials,
  registerNewUser,
  type AuthServiceDeps,
} from './auth-service';
import { defaultAuthDeps } from './default-deps';
import {
  loginSchema,
  registerFormSchema,
  toLoginRequestBody,
  toRegisterRequestBody,
  type LoginFormValues,
  type RegisterFormValues,
} from '@features/auth/model/schema';

export type AuthStatus = 'idle' | 'submitting' | 'authenticated' | 'error';

/** Generic field-error bag surfaced to forms. The `_form` key is form-level. */
export type FieldErrors = Readonly<Record<string, string>>;

export interface AuthViewModel<V> {
  readonly status: AuthStatus;
  readonly fieldErrors: FieldErrors;
  /** Authenticated session (only set when status === 'authenticated'). */
  readonly session: AuthSession | null;
  submit(values: V): Promise<void>;
  /** Reset back to idle (e.g. when the user starts editing again). */
  reset(): void;
}

/** Convert an ApiError to a FieldErrors bag per the mapping rules. */
export function apiErrorToFieldErrors(apiError: ApiError): FieldErrors {
  // Validation: spread server-provided field errors.
  if (apiError.kind === 'validation' && apiError.fieldErrors.length > 0) {
    const out: Record<string, string> = {};
    for (const fe of apiError.fieldErrors) {
      // Only keep the first message per field to avoid UI clutter.
      if (!(fe.field in out)) out[fe.field] = fe.message;
    }
    return out;
  }
  // Authentication (invalid credentials): generic form-level message.
  if (apiError.kind === 'authentication') {
    return { _form: 'Authentication failed' };
  }
  // Authorization (e.g. CROSS_SITE_ORIGIN): form-level, retryable.
  if (apiError.kind === 'authorization') {
    return { _form: apiError.retryable ? 'Request blocked, please retry' : 'Request blocked' };
  }
  // Retryable (502/503/504): form-level retry prompt.
  if (apiError.kind === 'retryable') {
    return { _form: 'Service temporarily unavailable, please retry' };
  }
  // Unknown / business / network: generic.
  return { _form: apiError.message || 'Unexpected error' };
}

/**
 * Build a login ViewModel. On submit: Zod-validate, call loginWithCredentials,
 * on success call onAuthenticated with the new session; on failure surface
 * field errors and reset status to error.
 */
export function useLoginViewModel(options?: {
  readonly deps?: AuthServiceDeps;
  readonly onAuthenticated?: (session: AuthSession) => void;
}): AuthViewModel<LoginFormValues> {
  const deps = options?.deps ?? defaultAuthDeps;
  const [status, setStatus] = useState<AuthStatus>('idle');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [session, setSession] = useState<AuthSession | null>(null);

  const submit = useCallback(
    async (values: LoginFormValues): Promise<void> => {
      // Client-side validation first.
      const parsed = loginSchema.safeParse(values);
      if (!parsed.success) {
        const out: Record<string, string> = {};
        for (const issue of parsed.error.issues) {
          const key = String(issue.path[0] ?? '_form');
          if (!(key in out)) out[key] = issue.message;
        }
        setFieldErrors(out);
        setStatus('error');
        return;
      }
      setStatus('submitting');
      setFieldErrors({});
      try {
        const result = await loginWithCredentials(deps, toLoginRequestBody(parsed.data));
        setSession(result.session);
        setStatus('authenticated');
        options?.onAuthenticated?.(result.session);
      } catch (err) {
        const apiError =
          err instanceof ApiErrorException
            ? err.apiError
            : ({
                kind: 'unknown',
                message: 'Unexpected error',
                retryable: false,
                fieldErrors: [],
              } as const);
        setFieldErrors(apiErrorToFieldErrors(apiError));
        setStatus('error');
      }
    },
    [deps, options],
  );

  const reset = useCallback(() => {
    setStatus('idle');
    setFieldErrors({});
  }, []);

  return { status, fieldErrors, session, submit, reset };
}

/**
 * Build a register ViewModel. Same shape as login but uses the register schema
 * (nickname + username + password + confirmPassword) and registerNewUser.
 */
export function useRegisterViewModel(options?: {
  readonly deps?: AuthServiceDeps;
  readonly onAuthenticated?: (session: AuthSession) => void;
}): AuthViewModel<RegisterFormValues> {
  const deps = options?.deps ?? defaultAuthDeps;
  const [status, setStatus] = useState<AuthStatus>('idle');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [session, setSession] = useState<AuthSession | null>(null);

  const submit = useCallback(
    async (values: RegisterFormValues): Promise<void> => {
      const parsed = registerFormSchema.safeParse(values);
      if (!parsed.success) {
        const out: Record<string, string> = {};
        for (const issue of parsed.error.issues) {
          const key = String(issue.path[0] ?? '_form');
          if (!(key in out)) out[key] = issue.message;
        }
        setFieldErrors(out);
        setStatus('error');
        return;
      }
      setStatus('submitting');
      setFieldErrors({});
      try {
        const result = await registerNewUser(deps, toRegisterRequestBody(parsed.data));
        setSession(result.session);
        setStatus('authenticated');
        options?.onAuthenticated?.(result.session);
      } catch (err) {
        const apiError =
          err instanceof ApiErrorException
            ? err.apiError
            : ({
                kind: 'unknown',
                message: 'Unexpected error',
                retryable: false,
                fieldErrors: [],
              } as const);
        setFieldErrors(apiErrorToFieldErrors(apiError));
        setStatus('error');
      }
    },
    [deps, options],
  );

  const reset = useCallback(() => {
    setStatus('idle');
    setFieldErrors({});
  }, []);

  return { status, fieldErrors, session, submit, reset };
}
