package ai.cerbur.crag.knowledge.reconcile;

import ai.cerbur.crag.common.annotation.ConstructorInjection;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionRetryService;
import ai.cerbur.crag.knowledge.core.ingestion.RetryDecision;
import ai.cerbur.crag.knowledge.core.ingestion.RetryPolicy;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.metrics.IngestionRecoveryMetrics;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Knowledge 摄取 Reconciler（plan_21/21.5）。
 *
 * <p>扫描滞留 PENDING/PROCESSING 文档，通过 RAG Ingestion Status RPC 查询权威 Job 状态，再按结果修复投影、终态化超时或创建新版本。 多实例按
 * Document CAS 抢占：retry CAS 与 apply projection CAS 都在事务内完成，只有一个实例能成功推进。
 *
 * <p>关键不变量（设计 §8.3）：
 *
 * <ul>
 *   <li>Reconciler 必须先查询 RAG 当前事实再推进或重试，不能只凭本地时间直接改写终态；
 *   <li>RAG PROCESSING 超过执行上限时，RAG 先以 CAS 终态化为安全超时失败，再由 Knowledge 决定是否创建新版本；
 *   <li>已达上限或确定性错误保持 FAILED，不创建新版本；
 *   <li>Status RPC 调用在数据库事务外执行，应用结果或创建新版本在事务内执行。
 * </ul>
 */
@Service
@ConstructorInjection
public class IngestionReconcileService {

  private static final Logger log = LoggerFactory.getLogger(IngestionReconcileService.class);

  private final DocumentDao documentDao;
  private final IngestionRetryService retryService;
  private final RagIngestionStatusClient statusClient;
  private final IngestionRecoveryMetrics metrics;
  private final RetryPolicy retryPolicy;
  private final ReconcilerProperties properties;

  public IngestionReconcileService(
      DocumentDao documentDao,
      IngestionRetryService retryService,
      RagIngestionStatusClient statusClient,
      IngestionRecoveryMetrics metrics,
      RetryPolicy retryPolicy,
      ReconcilerProperties properties) {
    this.documentDao = documentDao;
    this.retryService = retryService;
    this.statusClient = statusClient;
    this.metrics = metrics;
    this.retryPolicy = retryPolicy;
    this.properties = properties;
  }

  /**
   * 扫描并处理一批滞留文档。
   *
   * <p>Status RPC 调用在事务外；retry 与 projection 修复各自在 {@link IngestionRetryService} / CAS 事务内完成。
   *
   * @param batchSize 单批上限（覆盖默认配置，测试可注入更小值）
   * @param now 当前时间（Clock 驱动，测试可控）
   * @return 本批处理汇总
   */
  public ReconcileSummary reconcileBatch(int batchSize, Instant now) {
    LocalDateTime pendingThreshold =
        LocalDateTime.ofInstant(now.minus(properties.getPendingStaleThreshold()), ZoneOffset.UTC);
    LocalDateTime processingThreshold =
        LocalDateTime.ofInstant(
            now.minus(properties.getProcessingStaleThreshold()), ZoneOffset.UTC);
    List<DocumentEntity> candidates =
        documentDao
            .findStaleIngestionCandidates(pendingThreshold, processingThreshold, batchSize)
            .getContent();
    metrics.reconcileScan();
    metrics.reconcileCandidates(candidates.size());
    List<ReconcileItemResult> results = new ArrayList<>(candidates.size());
    for (DocumentEntity doc : candidates) {
      results.add(reconcileOne(doc, now));
    }
    return new ReconcileSummary(candidates.size(), results);
  }

  /**
   * 处理单个滞留文档。Status RPC 在事务外；推进决策按 RAG 权威状态进行。
   *
   * @param doc 滞留候选文档
   * @param now 当前时间
   * @return 处理结果
   */
  private ReconcileItemResult reconcileOne(DocumentEntity doc, Instant now) {
    Optional<RagIngestionStatus> ragStatus;
    try {
      ragStatus =
          statusClient.getStatus(
              doc.getTenantId(),
              doc.getKnowledgeBaseId(),
              doc.getDocId(),
              doc.getOperationVersion());
    } catch (RuntimeException e) {
      log.warn(
          "Reconciler RAG Status RPC unavailable — docId={} reason={}",
          doc.getDocId(),
          e.getMessage());
      metrics.reconcileRagUnavailable();
      return new ReconcileItemResult(
          doc.getDocId(),
          doc.getOperationVersion(),
          ReconcileOutcome.RAG_UNAVAILABLE,
          "rag unavailable");
    }
    if (ragStatus.isEmpty()) {
      // RAG 未找到 Job（可能事件丢失或 RAG 尚未消费）。视为可重试的 DISPATCH_MISSING。
      log.info(
          "Reconciler RAG has no Job for stale doc — docId={} op={} → auto retry",
          doc.getDocId(),
          doc.getOperationVersion());
      return attemptRetry(
          doc,
          "DISPATCH_MISSING",
          currentAttempt(doc),
          now,
          "rag job missing, treat as dispatch_missing");
    }
    RagIngestionStatus rag = ragStatus.get();
    String ragStatusValue = rag.status();
    // RAG 已到达 READY/FAILED/SUPERSEDED 终态：修复 Knowledge 投影或触发重试。
    if ("READY".equals(ragStatusValue) || "SUPERSEDED".equals(ragStatusValue)) {
      return repairProjection(doc, rag, "rag status=" + ragStatusValue);
    }
    if ("FAILED".equals(ragStatusValue)) {
      return attemptRetry(
          doc,
          rag.failureCategory(),
          Math.max(currentAttempt(doc), rag.attempt()),
          now,
          "rag failed category=" + rag.failureCategory());
    }
    if ("PROCESSING".equals(ragStatusValue)) {
      return handleProcessingStale(doc, rag, now);
    }
    // RAG PENDING：Knowledge 也 PENDING，状态一致，本轮 no-op（等待 RAG 处理）。
    return new ReconcileItemResult(
        doc.getDocId(),
        doc.getOperationVersion(),
        ReconcileOutcome.NO_ACTION,
        "rag pending, aligned");
  }

  /**
   * 处理 RAG PROCESSING 滞留：先 RAG CAS 终态化超时失败，再按 retry 策略创建新版本。
   *
   * <p>设计 §8.3：RAG PROCESSING 超过执行上限时，RAG 先以 CAS 终态化为安全超时失败，再由 Knowledge 决定是否创建新版本。
   */
  private ReconcileItemResult handleProcessingStale(
      DocumentEntity doc, RagIngestionStatus rag, Instant now) {
    Instant staleBefore = now.minus(properties.getProcessingStaleThreshold());
    Optional<RagIngestionStatus> timedOut;
    try {
      timedOut =
          statusClient.markTimedOut(
              doc.getTenantId(),
              doc.getKnowledgeBaseId(),
              doc.getDocId(),
              doc.getOperationVersion(),
              staleBefore);
    } catch (RuntimeException e) {
      log.warn(
          "Reconciler RAG markTimedOut unavailable — docId={} reason={}",
          doc.getDocId(),
          e.getMessage());
      metrics.reconcileRagUnavailable();
      return new ReconcileItemResult(
          doc.getDocId(),
          doc.getOperationVersion(),
          ReconcileOutcome.RAG_UNAVAILABLE,
          "rag unavailable");
    }
    if (timedOut.isEmpty()) {
      // RAG Job 未超时或 CAS 失败（可能刚推进）：本轮跳过，等下一轮。
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.NO_ACTION, "not stale yet");
    }
    metrics.reconcileTimedOut();
    // 超时终态化成功：现在视为 PROCESSING_TIMEOUT 失败，按 retry 策略决定是否创建新版本。
    return attemptRetry(
        doc, "PROCESSING_TIMEOUT", currentAttempt(doc), now, "rag processing timed out");
  }

  /**
   * 按 retry 策略尝试创建新版本（事务内由 IngestionRetryService 处理）。
   *
   * @param doc 当前文档
   * @param failureCategory 失败分类
   * @param currentAttempt 当前 attempt 序号
   * @param now 当前时间（日志关联）
   * @param reason 决策原因
   * @return 处理结果
   */
  private ReconcileItemResult attemptRetry(
      DocumentEntity doc, String failureCategory, int currentAttempt, Instant now, String reason) {
    RetryDecision decision = retryPolicy.decide(failureCategory, currentAttempt);
    if (!decision.retryable()) {
      log.info(
          "Reconciler skip retry — docId={} reason={} policy={}",
          doc.getDocId(),
          reason,
          decision.reason());
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.NO_ACTION, decision.reason());
    }
    try {
      retryService.retry(0L, doc.getTenantId(), doc.getKnowledgeBaseId(), doc.getDocId());
      metrics.reconcileRetried();
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.RETRIED, reason);
    } catch (VersionConflictException e) {
      log.info(
          "Reconciler retry CAS conflict — docId={} reason={}", doc.getDocId(), e.getMessage());
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.CONFLICT, "cas conflict");
    } catch (RuntimeException e) {
      log.warn("Reconciler retry failed — docId={} reason={}", doc.getDocId(), e.getMessage());
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.NO_ACTION, "retry failed");
    }
  }

  /**
   * 按 RAG 权威状态修复 Knowledge 投影（事务内 CAS）。
   *
   * <p>RAG 已到达 READY/SUPERSEDED 但 Knowledge 仍滞留 PENDING/PROCESSING：通过 apply projection CAS 修复。
   */
  private ReconcileItemResult repairProjection(
      DocumentEntity doc, RagIngestionStatus rag, String reason) {
    String targetStatus = "READY".equals(rag.status()) ? "READY" : "FAILED";
    if ("SUPERSEDED".equals(rag.status())) {
      // SUPERSEDED 表示 Knowledge 滞留但 RAG 已被更高版本接管：标记 FAILED 触发 retry 决策。
      targetStatus = "FAILED";
    }
    try {
      applyRagProjection(doc, rag, targetStatus);
      metrics.reconcileRepaired();
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.REPAIRED, reason);
    } catch (VersionConflictException e) {
      return new ReconcileItemResult(
          doc.getDocId(), doc.getOperationVersion(), ReconcileOutcome.CONFLICT, "cas conflict");
    }
  }

  /**
   * 将 RAG 权威状态应用到 Knowledge 投影（事务内 CAS）。
   *
   * <p>使用既有 {@code applyIngestionProjection} CAS，WHERE 匹配当前 operationVersion 与 version。
   */
  @org.springframework.transaction.annotation.Transactional
  public void applyRagProjection(DocumentEntity doc, RagIngestionStatus rag, String targetStatus) {
    LocalDateTime startedAt =
        rag.startedAtEpochMillis() == null
            ? null
            : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(rag.startedAtEpochMillis()), ZoneOffset.UTC);
    LocalDateTime completedAt =
        rag.completedAtEpochMillis() == null
            ? null
            : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(rag.completedAtEpochMillis()), ZoneOffset.UTC);
    String failureCategory = "FAILED".equals(targetStatus) ? rag.failureCategory() : null;
    String failureMessage = "FAILED".equals(targetStatus) ? rag.failureMessage() : null;
    documentDao.applyIngestionProjection(
        doc.getDocId(),
        doc.getTenantId(),
        doc.getKnowledgeBaseId(),
        doc.getOperationVersion(),
        doc.getVersion(),
        targetStatus,
        Math.max(currentAttempt(doc), rag.attempt()),
        rag.jobId(),
        failureCategory,
        failureMessage,
        startedAt,
        completedAt,
        null);
  }

  private static int currentAttempt(DocumentEntity doc) {
    Integer attempt = doc.getIngestionAttempt();
    return attempt == null ? 0 : attempt;
  }
}
