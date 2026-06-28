package ai.cerbur.crag.console.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.console.document.service.UploadValidation.Reason;
import ai.cerbur.crag.console.document.service.UploadValidation.UploadInvalidException;
import ai.cerbur.crag.console.document.service.UploadValidation.ValidatedUpload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * UploadValidation multipart 校验矩阵测试（plan_21/21.8）。
 *
 * <p>覆盖单 txt/md、空文件、双文件意图（Controller 层处理多文件，本测试验证 validate 对单个 MultipartFile 的判定）、超限、
 * 扩展名/MIME/UTF-8 错误。断言 SHA-256 正确与 chunk 顺序由 KnowledgeDocumentClientTest 验证。
 */
@DisplayName("UploadValidation multipart 校验矩阵")
class UploadValidationTest {

  private static final long MAX = UploadValidation.MAX_SIZE_BYTES;

  @Test
  @DisplayName("单 txt 文件 → VALID，fileType=TXT，sha256 正确")
  void singleTxtValidates() throws UploadInvalidException {
    byte[] content = "hello txt\n".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", content);

    ValidatedUpload upload = UploadValidation.validate(file, 1L, 100L, 123L);

    assertThat(upload.fileType()).isEqualTo("TXT");
    assertThat(upload.sizeBytes()).isEqualTo(content.length);
    assertThat(upload.sha256Hex()).isEqualTo(sha256Hex(content));
    assertThat(upload.originalFilename()).isEqualTo("note.txt");
  }

  @Test
  @DisplayName("单 md 文件 → VALID，fileType=MARKDOWN")
  void singleMdValidates() throws UploadInvalidException {
    byte[] content = "# title\n".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "readme.md", "text/markdown", content);

    ValidatedUpload upload = UploadValidation.validate(file, 1L, 100L, 123L);

    assertThat(upload.fileType()).isEqualTo("MARKDOWN");
    assertThat(upload.sha256Hex()).isEqualTo(sha256Hex(content));
  }

  @Test
  @DisplayName("MIME 为 application/octet-stream（浏览器未知类型）仍接受，扩展名优先")
  void octetStreamAcceptedWhenExtensionValid() throws UploadInvalidException {
    byte[] content = "data".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file =
        new MockMultipartFile("file", "a.txt", "application/octet-stream", content);

    ValidatedUpload upload = UploadValidation.validate(file, 1L, 100L, 123L);
    assertThat(upload.fileType()).isEqualTo("TXT");
  }

  @Test
  @DisplayName("空文件 → EMPTY_FILE")
  void emptyFileRejected() {
    MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.EMPTY_FILE);
  }

  @Test
  @DisplayName("file 参数为 null → MISSING_FILE")
  void missingFileRejected() {
    assertThatThrownBy(() -> UploadValidation.validate(null, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.MISSING_FILE);
  }

  @Test
  @DisplayName("不允许的扩展名 (.pdf) → UNSUPPORTED_EXTENSION")
  void unsupportedExtensionRejected() {
    MockMultipartFile file =
        new MockMultipartFile("file", "doc.pdf", "application/pdf", "x".getBytes());

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.UNSUPPORTED_EXTENSION);
  }

  @Test
  @DisplayName("扩展名 .txt 但 MIME 非 text/* → UNSUPPORTED_MIME")
  void unsupportedMimeRejected() {
    MockMultipartFile file =
        new MockMultipartFile("file", "a.txt", "application/zip", "x".getBytes());

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.UNSUPPORTED_MIME);
  }

  @Test
  @DisplayName("无扩展名 → UNSUPPORTED_EXTENSION")
  void noExtensionRejected() {
    MockMultipartFile file = new MockMultipartFile("file", "noext", "text/plain", "x".getBytes());

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.UNSUPPORTED_EXTENSION);
  }

  @Test
  @DisplayName("大小超过 10 MiB → TOO_LARGE")
  void tooLargeRejected() {
    // 构造略超上限的字节；不实际写入内容以保持测试快速
    byte[] oversized = new byte[(int) MAX + 1];
    MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", oversized);

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.TOO_LARGE);
  }

  @Test
  @DisplayName("恰好 10 MiB → VALID（边界）")
  void exactlyAtLimitValidates() throws UploadInvalidException {
    byte[] content = new byte[(int) MAX];
    MockMultipartFile file = new MockMultipartFile("file", "edge.txt", "text/plain", content);

    ValidatedUpload upload = UploadValidation.validate(file, 1L, 100L, 123L);
    assertThat(upload.sizeBytes()).isEqualTo(MAX);
    assertThat(upload.sha256Hex()).isEqualTo(sha256Hex(content));
  }

  @Test
  @DisplayName("非 UTF-8 字节 → NOT_UTF8")
  void nonUtf8Rejected() {
    // 0xFF 0xFE 不是合法 UTF-8 起始字节序列
    byte[] invalid = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41};
    MockMultipartFile file = new MockMultipartFile("file", "bad.txt", "text/plain", invalid);

    assertThatThrownBy(() -> UploadValidation.validate(file, 1L, 100L, 123L))
        .isInstanceOf(UploadInvalidException.class)
        .extracting(ex -> ((UploadInvalidException) ex).getReason())
        .isEqualTo(Reason.NOT_UTF8);
  }

  @Test
  @DisplayName("大小写扩展名不敏感（.TXT 与 .txt 等价）")
  void extensionCaseInsensitive() throws UploadInvalidException {
    byte[] content = "x".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "UP.TXT", "text/plain", content);

    ValidatedUpload upload = UploadValidation.validate(file, 1L, 100L, 123L);
    assertThat(upload.fileType()).isEqualTo("TXT");
  }

  @Test
  @DisplayName("ValidatedUpload record 字段完整保留")
  void validatedUploadRetainsFields() throws UploadInvalidException {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "f.md", "text/markdown", content);

    ValidatedUpload upload = UploadValidation.validate(file, 7L, 200L, 999L);
    assertThat(upload.tenantId()).isEqualTo(7L);
    assertThat(upload.knowledgeBaseId()).isEqualTo(200L);
    assertThat(upload.uploadedByUserId()).isEqualTo(999L);
    assertThat(upload.fileType()).isEqualTo("MARKDOWN");
  }

  private static String sha256Hex(byte[] data) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(data);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
