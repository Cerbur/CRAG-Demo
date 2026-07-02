/**
 * Tests for the pure {@link validateUpload} client-side pre-check.
 *
 * Boundary rules (plan_22/22.5):
 *   - Accept only `.txt` and `.md` extensions (case-insensitive).
 *   - Reject > 10 MiB (10 * 1024 * 1024 bytes). Exactly 10 MiB is allowed.
 *   - Empty filename / missing extension is invalid.
 *
 * `validateUpload` is PURE: it must not perform I/O or import services/http.
 * It is a UX pre-check only; the backend remains the source of truth
 * (validation error / 413 / 415 from the server overrides the client text).
 */
import { describe, it, expect } from 'vitest';
import { validateUpload, MAX_UPLOAD_BYTES } from './validate-upload';

const TEN_MIB = 10 * 1024 * 1024;

function file(name: string, size: number): File {
  // Construct a File with the requested size. The content does not matter for
  // the pure validator; we slice a zero-buffer to the required length.
  const buf = new Uint8Array(size);
  return new File([buf], name, { type: 'text/plain' });
}

describe('validateUpload extension rules', () => {
  it('accepts .txt', () => {
    expect(validateUpload(file('intro.txt', 100))).toEqual({ valid: true });
  });

  it('accepts .md', () => {
    expect(validateUpload(file('notes.md', 100))).toEqual({ valid: true });
  });

  it('accepts uppercase extensions case-insensitively', () => {
    expect(validateUpload(file('INTRO.TXT', 100))).toEqual({ valid: true });
    expect(validateUpload(file('Notes.MD', 100))).toEqual({ valid: true });
  });

  it('rejects unsupported extensions', () => {
    const r = validateUpload(file('photo.png', 100));
    expect(r.valid).toBe(false);
    if (!r.valid) expect(r.message.length).toBeGreaterThan(0);
  });

  it('rejects files with no extension', () => {
    expect(validateUpload(file('README', 100)).valid).toBe(false);
  });

  it('rejects .pdf even though it is a common document type', () => {
    expect(validateUpload(file('doc.pdf', 100)).valid).toBe(false);
  });
});

describe('validateUpload size rules', () => {
  it('accepts exactly 10 MiB (inclusive upper bound)', () => {
    expect(validateUpload(file('edge.txt', TEN_MIB))).toEqual({ valid: true });
  });

  it('rejects 10 MiB + 1 byte', () => {
    expect(validateUpload(file('too-big.txt', TEN_MIB + 1)).valid).toBe(false);
  });

  it('exports MAX_UPLOAD_BYTES as 10 MiB', () => {
    expect(MAX_UPLOAD_BYTES).toBe(TEN_MIB);
  });
});
