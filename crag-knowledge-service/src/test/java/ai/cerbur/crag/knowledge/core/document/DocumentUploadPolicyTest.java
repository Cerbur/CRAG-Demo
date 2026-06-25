package ai.cerbur.crag.knowledge.core.document;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.knowledge.filestore.CompletedUpload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DocumentUploadPolicy")
class DocumentUploadPolicyTest {

  private final DocumentUploadPolicy policy = new DocumentUploadPolicy();

  @TempDir Path tempDir;

  @Test
  @DisplayName("合法 metadata 通过：.txt 与 TXT 一致、大小合规、sha256 合法")
  void validMetadataPasses() {
    assertThatCode(
            () -> policy.validateMetadata(command("notes.txt", FileType.TXT, 3L, sha256("abc"))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("非法扩展名被拒绝")
  void rejectsUnsupportedExtension() {
    assertThatThrownBy(
            () -> policy.validateMetadata(command("payload.exe", FileType.TXT, 1L, sha256("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("声明类型与扩展名不一致被拒绝")
  void rejectsTypeMismatch() {
    assertThatThrownBy(
            () -> policy.validateMetadata(command("notes.txt", FileType.MARKDOWN, 1L, sha256("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("声明大小超过 10 MiB 被拒绝")
  void rejectsOversize() {
    assertThatThrownBy(
            () ->
                policy.validateMetadata(
                    command(
                        "notes.txt",
                        FileType.TXT,
                        DocumentUploadPolicy.MAX_SIZE_BYTES + 1,
                        sha256("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("sha256 非法格式被拒绝")
  void rejectsBadSha256() {
    assertThatThrownBy(
            () -> policy.validateMetadata(command("notes.txt", FileType.TXT, 1L, "not-hex")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("内容大小不匹配被拒绝")
  void rejectsContentSizeMismatch() throws Exception {
    CompletedUpload completed = writeTemp("abc");
    assertThatThrownBy(
            () ->
                policy.validateContent(
                    command("notes.txt", FileType.TXT, 99L, completed.sha256()), completed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("内容 sha256 不匹配被拒绝")
  void rejectsContentSha256Mismatch() throws Exception {
    CompletedUpload completed = writeTemp("abc");
    assertThatThrownBy(
            () ->
                policy.validateContent(
                    command("notes.txt", FileType.TXT, 3L, sha256("different")), completed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("非 UTF-8 内容被拒绝")
  void rejectsNonUtf8Content() throws Exception {
    byte[] raw = new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd};
    Path file = Files.createTempFile(tempDir, "non-utf8", ".tmp");
    Files.write(file, raw);
    CompletedUpload completed = new CompletedUpload(file, raw.length, sha256(raw));

    assertThatThrownBy(
            () ->
                policy.validateContent(
                    command("notes.txt", FileType.TXT, (long) raw.length, completed.sha256()),
                    completed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DocumentUploadCommand command(String filename, FileType type, long size, String sha) {
    return new DocumentUploadCommand(1L, 10L, 100L, filename, type, size, sha);
  }

  private CompletedUpload writeTemp(String content) throws Exception {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    Path file = Files.createTempFile(tempDir, "policy", ".tmp");
    Files.write(file, bytes);
    return new CompletedUpload(file, bytes.length, sha256(bytes));
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
