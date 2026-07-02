/**
 * Unit tests for the Chat (Open Query) mappers and pure model rules.
 *
 * Covers:
 *  - mapQueryResponseDto narrows the OpenAPI CitationResponse payload to a
 *    domain QuerySource list (reference/documentId/excerpt only).
 *  - Defensive narrowing: missing fields or non-array sources throw.
 *  - newMessageId() returns distinct ids.
 *  - createUserMessage / createAssistantPlaceholder apply the correct status.
 *  - markComplete / markFailed transition status and preserve identity.
 *  - validateQuestion enforces 1..2000 after trim and rejects non-strings.
 */
import { describe, it, expect } from 'vitest';
import {
  mapQueryResponseDto,
  newMessageId,
  createUserMessage,
  createAssistantPlaceholder,
  markComplete,
  markFailed,
  validateQuestion,
  ChatDtoError,
} from './mapper';

describe('mapQueryResponseDto', () => {
  it('maps a full response with sources', () => {
    const out = mapQueryResponseDto({
      answer: 'RAG 是…… [S1]',
      sources: [
        { reference: 'S1', documentId: '4001', excerpt: 'RAG 是……' },
        { reference: 'S2', documentId: '4002', excerpt: '另一种说法' },
      ],
    });
    expect(out.answer).toBe('RAG 是…… [S1]');
    expect(out.sources).toEqual([
      { reference: 'S1', documentId: '4001', excerpt: 'RAG 是……' },
      { reference: 'S2', documentId: '4002', excerpt: '另一种说法' },
    ]);
  });

  it('tolerates an empty sources array (no results)', () => {
    const out = mapQueryResponseDto({ answer: '没有找到相关来源', sources: [] });
    expect(out.sources).toEqual([]);
  });

  it('tolerates a missing sources field as empty', () => {
    const out = mapQueryResponseDto({ answer: 'ok' });
    expect(out.sources).toEqual([]);
  });

  it('throws when answer is missing or non-string', () => {
    expect(() => mapQueryResponseDto({ sources: [] })).toThrow(ChatDtoError);
    expect(() => mapQueryResponseDto({ answer: 123 })).toThrow(ChatDtoError);
    expect(() => mapQueryResponseDto('nope')).toThrow(ChatDtoError);
  });

  it('throws when a source is missing required fields', () => {
    expect(() =>
      mapQueryResponseDto({
        answer: 'ok',
        sources: [{ reference: 'S1' }],
      }),
    ).toThrow(ChatDtoError);
  });
});

describe('message helpers', () => {
  it('newMessageId returns distinct ids', () => {
    const a = newMessageId();
    const b = newMessageId();
    expect(a).not.toBe(b);
    expect(typeof a).toBe('string');
    expect(a.length).toBeGreaterThan(0);
  });

  it('createUserMessage has role user, empty sources, status sending', () => {
    const m = createUserMessage('hello');
    expect(m.role).toBe('user');
    expect(m.content).toBe('hello');
    expect(m.sources).toEqual([]);
    expect(m.status).toBe('sending');
  });

  it('createAssistantPlaceholder has role assistant and status sending', () => {
    const m = createAssistantPlaceholder();
    expect(m.role).toBe('assistant');
    expect(m.content).toBe('');
    expect(m.sources).toEqual([]);
    expect(m.status).toBe('sending');
  });

  it('markComplete sets status complete and fills content/sources', () => {
    const base = createAssistantPlaceholder();
    const out = markComplete(base, 'answer', [
      { reference: 'S1', documentId: '1', excerpt: 'ex' },
    ]);
    expect(out.id).toBe(base.id);
    expect(out.status).toBe('complete');
    expect(out.content).toBe('answer');
    expect(out.sources).toHaveLength(1);
  });

  it('markFailed sets status failed and leaves content/sources empty', () => {
    const base = createAssistantPlaceholder();
    const out = markFailed(base);
    expect(out.status).toBe('failed');
    expect(out.content).toBe('');
    expect(out.sources).toEqual([]);
  });
});

describe('validateQuestion', () => {
  it('trims and accepts 1..2000 chars', () => {
    expect(validateQuestion('  hello  ')).toBe('hello');
    expect(validateQuestion('a')).toBe('a');
    expect(validateQuestion('x'.repeat(2000))).toBe('x'.repeat(2000));
  });

  it('rejects empty / whitespace-only', () => {
    expect(() => validateQuestion('')).toThrow(ChatDtoError);
    expect(() => validateQuestion('   ')).toThrow(ChatDtoError);
  });

  it('rejects > 2000 chars after trim', () => {
    expect(() => validateQuestion('x'.repeat(2001))).toThrow(ChatDtoError);
  });

  it('rejects non-strings', () => {
    expect(() => validateQuestion(123 as unknown as string)).toThrow(ChatDtoError);
    expect(() => validateQuestion(null as unknown as string)).toThrow(ChatDtoError);
  });
});
