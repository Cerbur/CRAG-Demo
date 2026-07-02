import { describe, it, expect } from 'vitest';
import { mapApiError, mapTransportError, classifyErrorKind, type ApiError } from './api-error';
import type { ErrorDetailDto } from './types';

function baseDetail(overrides: Partial<ErrorDetailDto> = {}): ErrorDetailDto {
  return {
    message: 'X',
    traceId: 't-1',
    reason: 'X',
    retryable: false,
    fieldErrors: [],
    ...overrides,
  };
}

describe('classifyErrorKind', () => {
  it('maps business codes', () => {
    expect(classifyErrorKind(400, 40001)).toBe('validation');
    expect(classifyErrorKind(400, 40002)).toBe('validation');
    expect(classifyErrorKind(401, 40101)).toBe('authentication');
    expect(classifyErrorKind(401, 40102)).toBe('authentication');
    expect(classifyErrorKind(403, 40301)).toBe('authorization');
    expect(classifyErrorKind(404, 40401)).toBe('business');
    expect(classifyErrorKind(409, 40901)).toBe('business');
    expect(classifyErrorKind(409, 40902)).toBe('business');
    expect(classifyErrorKind(413, 41301)).toBe('business');
    expect(classifyErrorKind(415, 41501)).toBe('business');
    expect(classifyErrorKind(500, 50001)).toBe('unknown');
    expect(classifyErrorKind(502, 50201)).toBe('retryable');
    expect(classifyErrorKind(503, 50301)).toBe('retryable');
    expect(classifyErrorKind(504, 50401)).toBe('retryable');
  });

  it('falls back to HTTP status when code is absent', () => {
    expect(classifyErrorKind(400, undefined)).toBe('validation');
    expect(classifyErrorKind(401, undefined)).toBe('authentication');
    expect(classifyErrorKind(403, undefined)).toBe('authorization');
    expect(classifyErrorKind(404, undefined)).toBe('business');
    expect(classifyErrorKind(503, undefined)).toBe('retryable');
    expect(classifyErrorKind(418, undefined)).toBe('unknown');
  });
});

describe('mapApiError', () => {
  it('preserves traceId, retryable and fieldErrors; drops rejectedValue', () => {
    const err = mapApiError(
      400,
      baseDetail({
        reason: 'VALIDATION_ERROR',
        message: 'Validation failed',
        traceId: 'trace-abc',
        retryable: false,
        fieldErrors: [
          { field: 'name', message: 'must be 1-128', rejectedValue: 'secret-leak-attempt' },
          { field: 'password', message: 'too short', rejectedValue: null },
        ],
      }),
    );
    expect(err.kind).toBe('validation');
    expect(err.traceId).toBe('trace-abc');
    expect(err.retryable).toBe(false);
    expect(err.fieldErrors).toEqual([
      { field: 'name', message: 'must be 1-128' },
      { field: 'password', message: 'too short' },
    ]);
    // rejectedValue is not surfaced anywhere on the ApiError.
    expect(JSON.stringify(err)).not.toContain('secret-leak-attempt');
  });

  it('classifies 503 downstream as retryable via reason', () => {
    const err = mapApiError(
      503,
      baseDetail({
        reason: 'DOWNSTREAM_UNAVAILABLE',
        retryable: true,
        message: 'Downstream unavailable',
      }),
    );
    expect(err.kind).toBe('retryable');
    expect(err.retryable).toBe(true);
  });

  it('classifies 502 LLM_UNAVAILABLE as retryable', () => {
    const err = mapApiError(
      502,
      baseDetail({ reason: 'LLM_UNAVAILABLE', retryable: true, message: 'LLM unavailable' }),
    );
    expect(err.kind).toBe('retryable');
  });

  it('classifies 504 DOWNSTREAM_TIMEOUT as retryable', () => {
    const err = mapApiError(
      504,
      baseDetail({ reason: 'DOWNSTREAM_TIMEOUT', retryable: true, message: 'Downstream timeout' }),
    );
    expect(err.kind).toBe('retryable');
  });

  it('classifies INVALID_API_KEY / MISSING_API_KEY as authentication (Open)', () => {
    expect(mapApiError(401, baseDetail({ reason: 'INVALID_API_KEY' })).kind).toBe('authentication');
    expect(mapApiError(401, baseDetail({ reason: 'MISSING_API_KEY' })).kind).toBe('authentication');
  });

  it('classifies INVALID_QUERY as validation (Open)', () => {
    expect(mapApiError(400, baseDetail({ reason: 'INVALID_QUERY' })).kind).toBe('validation');
  });

  it('classifies CROSS_SITE_ORIGIN as authorization', () => {
    expect(mapApiError(403, baseDetail({ reason: 'CROSS_SITE_ORIGIN' })).kind).toBe(
      'authorization',
    );
  });

  it('falls back to HTTP status kind when reason is unknown', () => {
    const err = mapApiError(409, baseDetail({ reason: 'NOVEL_REASON' }));
    expect(err.kind).toBe('business');
  });

  it('does not leak the message into the kind decision', () => {
    // Same HTTP status, same code, but message implies auth — kind must stay validation.
    const err = mapApiError(
      400,
      baseDetail({ reason: 'VALIDATION_ERROR', message: 'password is wrong' }),
    );
    expect(err.kind).toBe('validation');
    expect(err.message).toBe('password is wrong');
  });

  it('satisfies the ApiError interface contract', () => {
    const err: ApiError = mapApiError(500, baseDetail({ reason: 'INTERNAL_ERROR' }));
    expect([
      'validation',
      'authentication',
      'authorization',
      'business',
      'retryable',
      'unknown',
    ]).toContain(err.kind);
    expect(typeof err.retryable).toBe('boolean');
    expect(Array.isArray(err.fieldErrors)).toBe(true);
  });
});

describe('mapTransportError', () => {
  it('network failures are retryable + unknown-ish', () => {
    const err = mapTransportError('network', { path: '/console-api/x', method: 'GET' });
    expect(err.kind).toBe('retryable');
    expect(err.retryable).toBe(true);
    expect(err.fieldErrors).toEqual([]);
    expect(err.message).toBe('Network error');
  });

  it('parse errors are unknown and not retryable', () => {
    const err = mapTransportError('parse');
    expect(err.kind).toBe('unknown');
    expect(err.retryable).toBe(false);
  });

  it('does not embed hostnames or auth material', () => {
    const err = mapTransportError('network');
    expect(JSON.stringify(err)).not.toMatch(/https?:\/\//);
    expect(JSON.stringify(err)).not.toMatch(/Bearer/i);
  });
});
