package ai.cerbur.crag.knowledge.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reconciler 配置（plan_21/21.5）。
 *
 * <p>设计默认值：PENDING 滞留阈值 2 分钟，PROCESSING 滞留阈值 15 分钟，单批扫描 50 个候选。测试可注入更短阈值， 不修改生产默认值。
 */
@Component
@ConfigurationProperties(prefix = "crag.ingestion.reconciler")
public class ReconcilerProperties {

  /** PENDING 滞留阈值（默认 2 分钟）。 */
  private Duration pendingStaleThreshold = Duration.ofMinutes(2);

  /** PROCESSING 滞留阈值（默认 15 分钟）。 */
  private Duration processingStaleThreshold = Duration.ofMinutes(15);

  /** 单批扫描上限（默认 50）。 */
  private int batchSize = 50;

  /** 调度间隔（默认 60 秒）。 */
  private Duration scheduleInterval = Duration.ofSeconds(60);

  public Duration getPendingStaleThreshold() {
    return pendingStaleThreshold;
  }

  public void setPendingStaleThreshold(Duration pendingStaleThreshold) {
    this.pendingStaleThreshold = pendingStaleThreshold;
  }

  public Duration getProcessingStaleThreshold() {
    return processingStaleThreshold;
  }

  public void setProcessingStaleThreshold(Duration processingStaleThreshold) {
    this.processingStaleThreshold = processingStaleThreshold;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getScheduleInterval() {
    return scheduleInterval;
  }

  public void setScheduleInterval(Duration scheduleInterval) {
    this.scheduleInterval = scheduleInterval;
  }
}
