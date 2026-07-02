/**
 * Unit tests for the pure API Key action matrix.
 *
 * Asserts the exact status → action mapping from plan_22 §22.6 acceptance:
 *   ACTIVE   → ['disable', 'rotate', 'revoke']
 *   DISABLED → ['enable', 'revoke']
 *   REVOKED  → []
 *   EXPIRED  → []
 */
import { describe, it, expect } from 'vitest';
import { allowedApiKeyActions } from './actions';

describe('allowedApiKeyActions', () => {
  it('ACTIVE exposes disable, rotate, revoke in that order', () => {
    expect(allowedApiKeyActions('ACTIVE')).toEqual(['disable', 'rotate', 'revoke']);
  });

  it('DISABLED exposes enable and revoke', () => {
    expect(allowedApiKeyActions('DISABLED')).toEqual(['enable', 'revoke']);
  });

  it('REVOKED exposes no actions (terminal)', () => {
    expect(allowedApiKeyActions('REVOKED')).toEqual([]);
  });

  it('EXPIRED exposes no actions (treated as terminal)', () => {
    expect(allowedApiKeyActions('EXPIRED')).toEqual([]);
  });

  it('returns a new array each call (immutability)', () => {
    const a = allowedApiKeyActions('ACTIVE');
    const b = allowedApiKeyActions('ACTIVE');
    expect(a).not.toBe(b);
    expect(a).toEqual(b);
  });

  it('the returned array is readonly at the type level (compile-time guard)', () => {
    const actions = allowedApiKeyActions('ACTIVE');
    // The type is ReadonlyArray; this is a compile-time guarantee. We assert
    // shape only at runtime here.
    expect(Array.isArray(actions)).toBe(true);
  });
});
