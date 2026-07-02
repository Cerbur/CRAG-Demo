/**
 * Pure client-side upload pre-check.
 *
 * Accepts only `.txt` and `.md` files up to 10 MiB. This is a UX pre-check ONLY;
 * the backend is the authoritative validator (验收标准: 不以客户端校验替代后端
 * 结果). When the server returns 413/415, the ViewModel surfaces the server's
 * safe message OVER any generic client text — see use-documents.ts.
 *
 * Pure: no I/O, no services/http import. Tested in validate-upload.test.ts.
 */

/** Maximum upload size: 10 MiB. */
export const MAX_UPLOAD_BYTES: number = 10 * 1024 * 1024;

/** Allowed extensions (lowercase, no leading dot). */
const ALLOWED_EXTENSIONS: ReadonlySet<string> = new Set(['txt', 'md']);

export type UploadValidation = { readonly valid: true } | { readonly valid: false; readonly message: string };

/** Extract the lowercase extension (without the dot) from a filename. */
function extensionOf(filename: string): string {
  const dot = filename.lastIndexOf('.');
  if (dot < 0 || dot === filename.length - 1) return '';
  return filename.slice(dot + 1).toLowerCase();
}

/**
 * Validate a File for upload. Returns `{ valid: true }` on success or
 * `{ valid: false, message }` with a Chinese UX message on failure.
 */
export function validateUpload(file: File): UploadValidation {
  const name = file.name ?? '';
  const ext = extensionOf(name);
  if (!ALLOWED_EXTENSIONS.has(ext)) {
    return {
      valid: false,
      message: '仅支持 .txt 与 .md 文件',
    };
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return {
      valid: false,
      message: '文件大小不能超过 10 MiB',
    };
  }
  return { valid: true };
}
