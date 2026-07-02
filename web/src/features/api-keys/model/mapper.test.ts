/**
 * Unit tests for the API Key mappers.
 *
 * Covers:
 *  - apiKeyId → id rename; IDs stay strings (never numericised).
 *  - status mapping, including EXPIRED being collapsed to REVOKED for the
 *    canonical status while preserved in statusForDisplay.
 *  - list nextPageToken tolerates both null and '' (the OpenAPI contract for
 *    ApiKeyListResponse allows null, unlike Knowledge/Document).
 *  - CreatedApiKey derives keyPrefix from completeKey and is always ACTIVE.
 *  - malformed payloads throw ApiKeyDtoError (defensive narrowing).
 *  - toCreateApiKeyRequest enforces name length 1..64 and ttlSeconds bounds.
 */
import { describe, it, expect } from 'vitest';
import {
  mapApiKeyDto,
  mapApiKeyListDto,
  mapCreatedApiKeyDto,
  toCreateApiKeyRequest,
  deriveKeyPrefix,
  ApiKeyDtoError,
} from './mapper';
import type { ApiKeyResponseDto } from './dto';

function apiKeyDto(overrides: Partial<ApiKeyResponseDto> = {}): ApiKeyResponseDto {
  return {
    apiKeyId: '5001',
    knowledgeBaseId: '3001',
    name: 'prod-key',
    status: 'ACTIVE',
    keyPrefix: 'crag_abcd',
    createdAt: '2026-07-02T09:00:00Z',
    expiresAt: null,
    ...overrides,
  };
}

describe('mapApiKeyDto', () => {
  it('renames apiKeyId → id and keeps it a string', () => {
    const item = mapApiKeyDto(apiKeyDto({ apiKeyId: '9876543210' }));
    expect(item.id).toBe('9876543210');
    expect(typeof item.id).toBe('string');
  });

  it('maps ACTIVE status straight through', () => {
    const item = mapApiKeyDto(apiKeyDto({ status: 'ACTIVE' }));
    expect(item.status).toBe('ACTIVE');
    expect(item.statusForDisplay).toBe('ACTIVE');
  });

  it('collapses EXPIRED to REVOKED for the canonical status but preserves display', () => {
    const item = mapApiKeyDto(apiKeyDto({ status: 'EXPIRED' }));
    expect(item.status).toBe('REVOKED');
    expect(item.statusForDisplay).toBe('EXPIRED');
  });

  it('preserves null expiresAt', () => {
    const item = mapApiKeyDto(apiKeyDto({ expiresAt: null }));
    expect(item.expiresAt).toBeNull();
  });

  it('throws on a missing required field', () => {
    expect(() => mapApiKeyDto({ ...apiKeyDto(), apiKeyId: '' })).toThrow(ApiKeyDtoError);
    expect(() => mapApiKeyDto({ ...apiKeyDto(), status: 'WEIRD' })).toThrow(ApiKeyDtoError);
    expect(() => mapApiKeyDto('nope')).toThrow(ApiKeyDtoError);
  });
});

describe('mapApiKeyListDto', () => {
  it('maps items and an empty string nextPageToken', () => {
    const page = mapApiKeyListDto({ items: [apiKeyDto()], nextPageToken: '' });
    expect(page.items).toHaveLength(1);
    expect(page.nextPageToken).toBe('');
  });

  it('tolerates null nextPageToken (ApiKeyListResponse allows null)', () => {
    const page = mapApiKeyListDto({ items: [], nextPageToken: null });
    expect(page.nextPageToken).toBe('');
    expect(page.items).toEqual([]);
  });

  it('preserves a non-empty nextPageToken', () => {
    const page = mapApiKeyListDto({ items: [apiKeyDto()], nextPageToken: 'next-1' });
    expect(page.nextPageToken).toBe('next-1');
  });

  it('throws on a non-array items field', () => {
    expect(() => mapApiKeyListDto({ items: 'nope', nextPageToken: '' })).toThrow(ApiKeyDtoError);
  });
});

describe('mapCreatedApiKeyDto', () => {
  it('derives keyPrefix from completeKey and sets status ACTIVE', () => {
    const created = mapCreatedApiKeyDto({
      apiKeyId: '5002',
      knowledgeBaseId: '3001',
      name: 'new-key',
      completeKey: 'crag_abcd_<PLACEHOLDER_SECRET>',
      expiresAt: null,
    });
    expect(created.id).toBe('5002');
    expect(created.status).toBe('ACTIVE');
    expect(created.statusForDisplay).toBe('ACTIVE');
    expect(created.completeKey).toBe('crag_abcd_<PLACEHOLDER_SECRET>');
    expect(created.keyPrefix).toBe('crag_abcd');
  });

  it('preserves expiresAt', () => {
    const created = mapCreatedApiKeyDto({
      apiKeyId: '5003',
      knowledgeBaseId: '3001',
      name: 'ttl-key',
      completeKey: 'crag_xy12_<PLACEHOLDER_SECRET>',
      expiresAt: '2026-12-31T23:59:59Z',
    });
    expect(created.expiresAt).toBe('2026-12-31T23:59:59Z');
  });

  it('throws when completeKey is missing', () => {
    expect(() =>
      mapCreatedApiKeyDto({ apiKeyId: '1', knowledgeBaseId: '1', name: 'x' }),
    ).toThrow(ApiKeyDtoError);
  });
});

describe('deriveKeyPrefix', () => {
  it('extracts the crag_<4-char> prefix', () => {
    expect(deriveKeyPrefix('crag_abcd_secret_body_here')).toBe('crag_abcd');
  });

  it('falls back to first 12 chars for an unexpected shape', () => {
    expect(deriveKeyPrefix('unusualkeynoseparator')).toBe('unusualkeyno');
  });
});

describe('toCreateApiKeyRequest', () => {
  it('trims and validates name length 1..64', () => {
    expect(toCreateApiKeyRequest('  hello  ')).toEqual({ name: 'hello' });
    expect(() => toCreateApiKeyRequest('')).toThrow(ApiKeyDtoError);
    expect(() => toCreateApiKeyRequest('a'.repeat(65))).toThrow(ApiKeyDtoError);
  });

  it('accepts an optional ttlSeconds within 0..31536000', () => {
    expect(toCreateApiKeyRequest('k', 0)).toEqual({ name: 'k', ttlSeconds: 0 });
    expect(toCreateApiKeyRequest('k', 31_536_000)).toEqual({
      name: 'k',
      ttlSeconds: 31_536_000,
    });
  });

  it('rejects out-of-range or non-integer ttlSeconds', () => {
    expect(() => toCreateApiKeyRequest('k', -1)).toThrow(ApiKeyDtoError);
    expect(() => toCreateApiKeyRequest('k', 31_536_001)).toThrow(ApiKeyDtoError);
    expect(() => toCreateApiKeyRequest('k', 1.5)).toThrow(ApiKeyDtoError);
  });
});
