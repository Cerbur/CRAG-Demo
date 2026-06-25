package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.filestore.CompletedUpload;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 上传校验策略：纯逻辑，不依赖 DAO。
 *
 * <p>{@link #validateMetadata} 在接收任何字节前校验扩展名/类型一致性、大小上限与 sha256 格式； {@link #validateContent}
 * 在接收完成后校验实际 大小、实际 sha256 与 UTF-8 有效性。校验失败抛 {@link IllegalArgumentException}，调用方据此不创建业务记录。
 */
@Component
public class DocumentUploadPolicy {

  /** 单次上传大小上限：10 MiB。 */
  public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /** 接收前校验 metadata：扩展名推导类型与声明类型一致、声明大小不超上限、sha256 为 64 位十六进制。 */
  public void validateMetadata(DocumentUploadCommand command) {
    FileType derived = FileType.fromFilename(command.originalFilename());
    if (derived != command.fileType()) {
      throw new IllegalArgumentException(
          "declared fileType " + command.fileType() + " does not match filename extension");
    }
    if (command.declaredSizeBytes() > MAX_SIZE_BYTES) {
      throw new IllegalArgumentException("declared size exceeds the 10 MiB limit");
    }
    if (!SHA256_HEX.matcher(command.declaredSha256().toLowerCase()).matches()) {
      throw new IllegalArgumentException("declared sha256 must be 64 lowercase hex chars");
    }
  }

  /** 接收后校验内容：实际大小等于声明、实际 sha256 等于声明、内容为合法 UTF-8。 */
  public void validateContent(DocumentUploadCommand command, CompletedUpload completed) {
    if (completed.sizeBytes() != command.declaredSizeBytes()) {
      throw new IllegalArgumentException(
          "size mismatch: declared "
              + command.declaredSizeBytes()
              + ", actual "
              + completed.sizeBytes());
    }
    if (!completed.sha256().equals(command.declaredSha256().toLowerCase())) {
      throw new IllegalArgumentException("sha256 mismatch");
    }
    validateUtf8(completed.tempPath());
  }

  private void validateUtf8(Path path) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (Reader reader = new InputStreamReader(new FileInputStream(path.toFile()), decoder)) {
      char[] buffer = new char[8192];
      while (reader.read(buffer) >= 0) {
        // drain to surface any decoding error
      }
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IllegalArgumentException("file content is not valid UTF-8", e);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read temp file for UTF-8 validation", e);
    }
  }
}
