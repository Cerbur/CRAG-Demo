package ai.cerbur.crag.knowledge.filestore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 流式上传的临时写入槽：边写入临时文件边累积 sha256 与字节数。
 *
 * <p>{@link #finish()} 关闭流并返回 {@link CompletedUpload}；{@link #closeQuietly()}
 * 关闭并删除临时文件，用于上传中断或回滚清理。
 */
public final class TempFileSink {

  private final Path tempPath;
  private final OutputStream output;
  private final MessageDigest digest;
  private long sizeBytes;
  private boolean finished;

  TempFileSink(Path tempPath) throws IOException {
    this.tempPath = tempPath;
    this.digest = newSha256();
    this.output = new DigestOutputStream(Files.newOutputStream(tempPath), digest);
  }

  /** 写入一段字节；同步更新 sha256 与字节数。 */
  public void write(byte[] bytes, int offset, int length) throws IOException {
    if (finished) {
      throw new IllegalStateException("sink already finished");
    }
    output.write(bytes, offset, length);
    sizeBytes += length;
  }

  /** 关闭流并固化 sha256 与字节数，返回完成快照。 */
  public CompletedUpload finish() throws IOException {
    if (finished) {
      throw new IllegalStateException("sink already finished");
    }
    finished = true;
    output.close();
    return new CompletedUpload(tempPath, sizeBytes, hex(digest.digest()));
  }

  /** 关闭并静默删除临时文件，用于中断或失败清理。 */
  public void closeQuietly() {
    finished = true;
    try {
      output.close();
    } catch (IOException ignored) {
      // best-effort close
    }
    try {
      Files.deleteIfExists(tempPath);
    } catch (IOException ignored) {
      // best-effort delete; orphaned temp files handled by future reconciler
    }
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
