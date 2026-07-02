/**
 * Pure API Key domain rules.
 *
 * Lives under features/api-keys/model (NO services/http import — the
 * architecture test forbids it). The status action matrix is the source of
 * truth for which lifecycle actions the View may surface for a key:
 *
 *  - ACTIVE   → disable, rotate, revoke
 *  - DISABLED → enable, revoke
 *  - REVOKED  → (none — terminal)
 *  - EXPIRED  → (none — treated as terminal even though it is a server-only
 *               status; the user cannot act on an expired key)
 *
 * `EXPIRED` is part of {@link ApiKeyStatusForActions} so that list rows whose
 * server status is EXPIRED map to "no actions"; the public {@link ApiKeyStatus}
 * exposed to the View does not include EXPIRED because no key the user can
 * manage is in that state from their perspective (it is read-only display).
 */

/** Lifecycle action kinds that the View may surface per row. */
export type ApiKeyAction = 'disable' | 'enable' | 'rotate' | 'revoke';

/**
 * The set of statuses the action matrix accepts. Includes EXPIRED so callers
 * that receive an EXPIRED projection (server-side only) do not throw — they
 * simply get an empty action list.
 */
export type ApiKeyStatusForActions = 'ACTIVE' | 'DISABLED' | 'REVOKED' | 'EXPIRED';

/**
 * The user-facing status. The View renders these as tags; EXPIRED rows are
 * displayed as DISABLED-equivalent terminal with no actions.
 */
export type ApiKeyStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED';

/**
 * Return the immutable, ordered list of lifecycle actions allowed for a key in
 * the given status. Pure: same input always yields the same output, no I/O.
 *
 * Matrix (per plan_22 §22.6 acceptance):
 *   ACTIVE   → ['disable', 'rotate', 'revoke']
 *   DISABLED → ['enable', 'revoke']
 *   REVOKED  → []
 *   EXPIRED  → []
 */
export function allowedApiKeyActions(
  status: ApiKeyStatusForActions,
): ReadonlyArray<ApiKeyAction> {
  switch (status) {
    case 'ACTIVE':
      return ['disable', 'rotate', 'revoke'];
    case 'DISABLED':
      return ['enable', 'revoke'];
    case 'REVOKED':
    case 'EXPIRED':
      return [];
    default:
      // Exhaustiveness guard — if a new status is added to the union without
      // updating this switch, TypeScript errors here.
      return [];
  }
}
