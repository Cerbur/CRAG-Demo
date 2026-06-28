package ai.cerbur.crag.console.config.multipart;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Console 文档上传配置（plan_21/21.8）。
 *
 * <p>绑定 {@code crag.console.upload.*}。{@code maxFileSizeBytes} 与 {@code maxRequestSizeBytes} 应保持与
 * {@link ai.cerbur.crag.console.document.service.UploadValidation#MAX_SIZE_BYTES} 一致（10 MiB），让
 * Spring multipart resolver 与 UploadValidation 双重保险。超过上限时 resolver 先抛
 * MaxUploadSizeExceededException，由 GlobalExceptionHandler 映射为 41301。
 */
@Validated
@ConfigurationProperties(prefix = "crag.console.upload")
public class ConsoleUploadProperties {

  /** 单文件大小上限（字节）。默认 10 MiB。 */
  @Min(1)
  private long maxFileSizeBytes = 10L * 1024 * 1024;

  /** 整个 multipart 请求大小上限（字节）。默认 10 MiB + 1 KiB 余量。 */
  @Min(1)
  private long maxRequestSizeBytes = 10L * 1024 * 1024 + 1024;

  /** 上传 gRPC 流式调用 deadline（毫秒）。 */
  private int deadlineMillis = 60000;

  public long getMaxFileSizeBytes() {
    return maxFileSizeBytes;
  }

  public void setMaxFileSizeBytes(long maxFileSizeBytes) {
    this.maxFileSizeBytes = maxFileSizeBytes;
  }

  public long getMaxRequestSizeBytes() {
    return maxRequestSizeBytes;
  }

  public void setMaxRequestSizeBytes(long maxRequestSizeBytes) {
    this.maxRequestSizeBytes = maxRequestSizeBytes;
  }

  public int getDeadlineMillis() {
    return deadlineMillis;
  }

  public void setDeadlineMillis(int deadlineMillis) {
    this.deadlineMillis = deadlineMillis;
  }
}
