/**
 * Mapper tests for Knowledge DTO → domain Model conversion.
 *
 * Covers: id stays string, field name remapping, list pagination token,
 * partial-success apiKeyReady=false preserved, malformed DTO throws.
 */
import { describe, it, expect } from 'vitest';
import {
  mapKnowledgeBaseDto,
  mapKnowledgeBaseListDto,
  KnowledgeDtoError,
} from './mapper';

const validDto = {
  knowledgeBaseId: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
};

describe('mapKnowledgeBaseDto', () => {
  it('maps all fields, remapping knowledgeBaseId→id (string preserved)', () => {
    const m = mapKnowledgeBaseDto(validDto);
    expect(m).toEqual({
      id: '3001',
      tenantId: '2001',
      name: '产品文档',
      apiKeyReady: true,
      createdAt: '2026-07-02T09:00:00Z',
      updatedAt: '2026-07-02T09:00:00Z',
    });
    // Explicit type assertion: the id is a string, not a number, even for
    // all-digit wire values.
    expect(typeof m.id).toBe('string');
  });

  it('preserves partial-success apiKeyReady=false', () => {
    const m = mapKnowledgeBaseDto({ ...validDto, apiKeyReady: false });
    expect(m.apiKeyReady).toBe(false);
  });

  it('throws KnowledgeDtoError when the payload is not an object', () => {
    expect(() => mapKnowledgeBaseDto(null)).toThrow(KnowledgeDtoError);
    expect(() => mapKnowledgeBaseDto('nope')).toThrow(KnowledgeDtoError);
  });

  it('throws when required string fields are missing or wrong type', () => {
    expect(() => mapKnowledgeBaseDto({ ...validDto, knowledgeBaseId: 3001 })).toThrow(
      KnowledgeDtoError,
    );
    expect(() => mapKnowledgeBaseDto({ ...validDto, name: '' })).toThrow(KnowledgeDtoError);
    expect(() => mapKnowledgeBaseDto({ ...validDto, tenantId: undefined })).toThrow(
      KnowledgeDtoError,
    );
  });

  it('throws when apiKeyReady is not a boolean', () => {
    expect(() => mapKnowledgeBaseDto({ ...validDto, apiKeyReady: 'true' })).toThrow(
      KnowledgeDtoError,
    );
    // Missing apiKeyReady is also a contract violation.
    const { apiKeyReady: _omit, ...rest } = validDto;
    void _omit;
    expect(() => mapKnowledgeBaseDto(rest)).toThrow(KnowledgeDtoError);
  });
});

describe('mapKnowledgeBaseListDto', () => {
  it('maps items and preserves nextPageToken verbatim', () => {
    const page = mapKnowledgeBaseListDto({
      items: [validDto, { ...validDto, knowledgeBaseId: '3002' }],
      nextPageToken: 'cursor-abc',
    });
    expect(page.items).toHaveLength(2);
    expect(page.items[0]!.id).toBe('3001');
    expect(page.items[1]!.id).toBe('3002');
    expect(page.nextPageToken).toBe('cursor-abc');
  });

  it('treats empty nextPageToken as end-of-list sentinel', () => {
    const page = mapKnowledgeBaseListDto({ items: [], nextPageToken: '' });
    expect(page.items).toEqual([]);
    expect(page.nextPageToken).toBe('');
  });

  it('throws when items is missing or not an array', () => {
    expect(() => mapKnowledgeBaseListDto({ nextPageToken: '' })).toThrow(KnowledgeDtoError);
    expect(() =>
      mapKnowledgeBaseListDto({ items: 'not-array', nextPageToken: '' }),
    ).toThrow(KnowledgeDtoError);
  });

  it('throws when nextPageToken is not a string', () => {
    expect(() =>
      mapKnowledgeBaseListDto({ items: [], nextPageToken: 42 }),
    ).toThrow(KnowledgeDtoError);
  });
});
