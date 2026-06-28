package ai.cerbur.crag.console.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * Console 文档上传校验（plan_21/21.8）。
 *
 * <p>校验矩阵：单文件、扩展名 (.txt/.md)、MIME 类型、UTF-8 解码、大小上限 (10 MiB)。先计算 size 与 SHA-256，再以 metadata-first
 * 顺序交给 KnowledgeDocumentClient。不在日志/异常/DB 事务中保留文件内容。
 *
 * <p>校验失败抛出 {@link UploadInvalidException}，由 GlobalExceptionHandler 映射为相应错误码（UPLOAD_TOO_LARGE /
 * UNSUPPORTED_MEDIA_TYPE / VALIDATION_ERROR）。
 */
public final class UploadValidation {

  /** 上传大小上限：10 MiB。 */
  public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

  /** 允许的扩展名（小写，含点）。 */
  static final Set<String> ALLOWED_EXTENSIONS = Set.of(".txt", ".md");

  /** 允许的 MIME 类型。 */
  static final Set<String> ALLOWED_MIME_TYPES =
      Set.of("text/plain", "text/markdown", "application/octet-stream");

  /** proto 接受的 file_type 值。 */
  static final String FILE_TYPE_TXT = "TXT";

  static final String FILE_TYPE_MARKDOWN = "MARKDOWN";

  private UploadValidation() {}

  /**
   * 校验 MultipartFile 并计算 SHA-256/size/fileType。
   *
   * <p>校验顺序：单文件 → 非空 → 扩展名 → MIME → 大小 → UTF-8。返回的 {@link ValidatedUpload} 持有文件内容字节流，
   * 调用方负责立即消费并丢弃，不长期持有。
   */
  public static ValidatedUpload validate(
      MultipartFile file, long tenantId, long knowledgeBaseId, long uploadedByUserId)
      throws UploadInvalidException {
    if (file == null) {
      throw new UploadInvalidException(Reason.MISSING_FILE);
    }
    if (file.isEmpty()) {
      throw new UploadInvalidException(Reason.EMPTY_FILE);
    }
    String filename = file.getOriginalFilename();
    String extension = extensionOf(filename);
    String fileType = fileTypeForExtension(extension);
    if (fileType == null) {
      throw new UploadInvalidException(Reason.UNSUPPORTED_EXTENSION);
    }
    String contentType = file.getContentType();
    if (contentType != null && !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
      throw new UploadInvalidException(Reason.UNSUPPORTED_MIME);
    }
    long size = file.getSize();
    if (size > MAX_SIZE_BYTES) {
      throw new UploadInvalidException(Reason.TOO_LARGE);
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UploadInvalidException(Reason.READ_FAILED);
    }
    if (bytes.length > MAX_SIZE_BYTES) {
      throw new UploadInvalidException(Reason.TOO_LARGE);
    }
    if (!isValidUtf8(bytes)) {
      throw new UploadInvalidException(Reason.NOT_UTF8);
    }
    String sha256Hex = sha256Hex(bytes);
    return new ValidatedUpload(
        tenantId,
        knowledgeBaseId,
        uploadedByUserId,
        filename == null ? "" : filename,
        fileType,
        bytes.length,
        sha256Hex,
        new ByteArrayInputStream(bytes));
  }

  /** 在发送 metadata 前再次确认不变量（大小/非空）；防御性。 */
  static void recheckInvariants(ValidatedUpload upload) throws UploadInvalidException {
    if (upload == null) {
      throw new UploadInvalidException(Reason.MISSING_FILE);
    }
    if (upload.sizeBytes() <= 0) {
      throw new UploadInvalidException(Reason.EMPTY_FILE);
    }
    if (upload.sizeBytes() > MAX_SIZE_BYTES) {
      throw new UploadInvalidException(Reason.TOO_LARGE);
    }
    if (!FILE_TYPE_TXT.equals(upload.fileType()) && !FILE_TYPE_MARKDOWN.equals(upload.fileType())) {
      throw new UploadInvalidException(Reason.UNSUPPORTED_EXTENSION);
    }
  }

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    if (dot < 0 || dot == filename.length() - 1) {
      return "";
    }
    return filename.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static String fileTypeForExtension(String extension) {
    return switch (extension) {
      case ".txt" -> FILE_TYPE_TXT;
      case ".md" -> FILE_TYPE_MARKDOWN;
      default -> null;
    };
  }

  private static boolean isValidUtf8(byte[] bytes) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  private static String sha256Hex(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(data);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** 校验失败原因，用于稳定映射到错误码。 */
  public enum Reason {
    MISSING_FILE,
    EMPTY_FILE,
    UNSUPPORTED_EXTENSION,
    UNSUPPORTED_MIME,
    TOO_LARGE,
    NOT_UTF8,
    READ_FAILED
  }

  /**
   * 校验失败异常。message 不含文件内容；reason 用于 GlobalExceptionHandler 映射。
   *
   * <p>{@link #TOO_LARGE} 映射 41301；{@link #UNSUPPORTED_EXTENSION}/{@link #UNSUPPORTED_MIME} 映射
   * 41501；其余映射 40001 VALIDATION_ERROR。
   */
  public static class UploadInvalidException extends RuntimeException {
    private final Reason reason;

    public UploadInvalidException(Reason reason) {
      super(reason.name());
      this.reason = reason;
    }

    public UploadInvalidException(String message) {
      super(message);
      this.reason = Reason.READ_FAILED;
    }

    public Reason getReason() {
      return reason;
    }
  }

  /** 已校验的上传材料：metadata + 内容字节流。调用方应立即消费并丢弃。 */
  public record ValidatedUpload(
      long tenantId,
      long knowledgeBaseId,
      long uploadedByUserId,
      String originalFilename,
      String fileType,
      long sizeBytes,
      String sha256Hex,
      InputStream content) {}
}
