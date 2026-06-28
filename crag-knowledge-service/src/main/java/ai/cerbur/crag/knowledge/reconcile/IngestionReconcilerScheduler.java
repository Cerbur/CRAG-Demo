package ai.cerbur.crag.knowledge.reconcile;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

/**
 * Ingestion Reconciler 调度入口（plan_21/21.5）。
 *
 * <p>由 Spring {@link TaskScheduler} Bean 驱动周期性扫描滞留文档。多实例部署时，每个实例独立调度， 但 Document CAS
 * 保证只有一个实例能成功推进或创建新版本（并发抢占失败方记录 CONFLICT）。
 *
 * <p>调度间隔由 {@link ReconcilerProperties#getScheduleInterval()} 配置，默认 60 秒。
 */
@Component
public class IngestionReconcilerScheduler {

  private static final Logger log = LoggerFactory.getLogger(IngestionReconcilerScheduler.class);

  @Autowired private IngestionReconcileService reconcileService;
  @Autowired private ReconcilerProperties properties;

  @Autowired(required = false)
  private TaskScheduler taskScheduler;

  /** 注册周期性 reconcile 任务。 */
  @PostConstruct
  public void schedule() {
    if (taskScheduler == null) {
      log.warn("Ingestion Reconciler disabled: no TaskScheduler Bean available");
      return;
    }
    Duration interval = properties.getScheduleInterval();
    PeriodicTrigger trigger = new PeriodicTrigger(interval);
    trigger.setFixedRate(true);
    taskScheduler.schedule(
        () -> {
          try {
            ReconcileSummary summary =
                reconcileService.reconcileBatch(properties.getBatchSize(), Instant.now());
            if (summary.scanned() > 0) {
              log.info(
                  "Ingestion Reconciler batch completed — scanned={} repaired={} timedOut={} retried={} conflicts={} ragUnavailable={}",
                  summary.scanned(),
                  summary.countBy(ReconcileOutcome.REPAIRED),
                  summary.countBy(ReconcileOutcome.TIMED_OUT),
                  summary.countBy(ReconcileOutcome.RETRIED),
                  summary.countBy(ReconcileOutcome.CONFLICT),
                  summary.countBy(ReconcileOutcome.RAG_UNAVAILABLE));
            }
          } catch (RuntimeException e) {
            log.warn("Ingestion Reconciler batch failed — reason={}", e.getMessage());
          }
        },
        trigger);
  }
}
