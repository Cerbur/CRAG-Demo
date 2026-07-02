/**
 * Tests for the Document DTO → domain Model mapper.
 *
 * Covers:
 *  - Full happy-path mapping (docId→id, originalFilename→filename, etc.).
 *  - Null nullable fields (failureMessage=null, startedAt=null, completedAt=null).
 *  - Empty-string failureMessage/failureCategory from the OpenAPI examples map
 *    to null (so the View never shows an empty "failure" line).
 *  - updatedAt prefers completedAt, falls back to startedAt, else null.
 *  - IDs remain strings throughout (never numericised).
 *  - Malformed payloads throw DocumentDtoError.
 */
import { describe, it, expect } from 'vitest';
import {
  mapDocumentDto,
  mapDocumentListDto,
  DocumentDtoError,
} from './mapper';
import type { DocumentResponseDto } from './dto';

const baseDto: DocumentResponseDto = {
  docId: '4001',
  knowledgeBaseId: '3001',
  originalFilename: 'intro.txt',
  fileType: 'TXT',
  sizeBytes: 128,
  ingestionStatus: 'READY',
  operationVersion: '1',
  attempt: 1,
  failureCategory: '',
  failureMessage: '',
  retryable: false,
  startedAt: '2026-06-29T09:01:00Z',
  completedAt: '2026-06-29T09:01:30Z',
};

describe('mapDocumentDto happy path', () => {
  it('maps a READY document with completedAt as updatedAt', () => {
    const item = mapDocumentDto(baseDto);
    expect(item).toEqual({
      id: '4001',
      knowledgeBaseId: '3001',
      filename: 'intro.txt',
      sizeBytes: 128,
      status: 'READY',
      attempt: 1,
      retryable: false,
      failureMessage: null,
      updatedAt: '2026-06-29T09:01:30Z',
    });
  });

  it('maps a PENDING document with null timestamps to updatedAt=null', () => {
    const item = mapDocumentDto({
      ...baseDto,
      ingestionStatus: 'PENDING',
      startedAt: null,
      completedAt: null,
    });
    expect(item.status).toBe('PENDING');
    expect(item.updatedAt).toBeNull();
  });

  it('falls back to startedAt when completedAt is null', () => {
    const item = mapDocumentDto({
      ...baseDto,
      completedAt: null,
      startedAt: '2026-06-29T09:05:00Z',
    });
    expect(item.updatedAt).toBe('2026-06-29T09:05:00Z');
  });

  it('maps a FAILED retryable document with a safe failureMessage', () => {
    const item = mapDocumentDto({
      ...baseDto,
      ingestionStatus: 'FAILED',
      attempt: 2,
      failureCategory: 'DISPATCH_MISSING',
      failureMessage: 'ingestion dispatch missing',
      retryable: true,
    });
    expect(item.status).toBe('FAILED');
    expect(item.attempt).toBe(2);
    expect(item.retryable).toBe(true);
    expect(item.failureMessage).toBe('ingestion dispatch missing');
  });

  it('treats empty-string failureMessage as null', () => {
    const item = mapDocumentDto({ ...baseDto, failureMessage: '' });
    expect(item.failureMessage).toBeNull();
  });
});

describe('mapDocumentDto keeps IDs as strings', () => {
  it('does not numericise large docIds', () => {
    const item = mapDocumentDto({ ...baseDto, docId: '9007199254740993' });
    // 9007199254740993 > MAX_SAFE_INTEGER; numericising would lose precision.
    expect(item.id).toBe('9007199254740993');
  });
});

describe('mapDocumentListDto', () => {
  it('maps a list with items and nextPageToken', () => {
    const page = mapDocumentListDto({
      items: [baseDto, { ...baseDto, docId: '4002' }],
      nextPageToken: 'next1',
    });
    expect(page.items.length).toBe(2);
    expect(page.items[0]!.id).toBe('4001');
    expect(page.items[1]!.id).toBe('4002');
    expect(page.nextPageToken).toBe('next1');
  });

  it('maps an empty list', () => {
    const page = mapDocumentListDto({ items: [], nextPageToken: '' });
    expect(page.items).toEqual([]);
    expect(page.nextPageToken).toBe('');
  });
});

describe('mapDocumentDto rejects malformed payloads', () => {
  it('throws on non-object', () => {
    expect(() => mapDocumentDto(null)).toThrow(DocumentDtoError);
    expect(() => mapDocumentDto('hello')).toThrow(DocumentDtoError);
  });

  it('throws on missing docId', () => {
    const { docId, ...rest } = baseDto;
    void docId;
    expect(() => mapDocumentDto(rest)).toThrow(DocumentDtoError);
  });

  it('throws on unknown ingestionStatus', () => {
    expect(() =>
      mapDocumentDto({ ...baseDto, ingestionStatus: 'WAT' as never }),
    ).toThrow(DocumentDtoError);
  });

  it('throws on negative sizeBytes', () => {
    expect(() => mapDocumentDto({ ...baseDto, sizeBytes: -1 })).toThrow(DocumentDtoError);
  });

  it('list mapper throws when items is not an array', () => {
    expect(() => mapDocumentListDto({ items: 'nope', nextPageToken: '' })).toThrow(
      DocumentDtoError,
    );
  });
});
