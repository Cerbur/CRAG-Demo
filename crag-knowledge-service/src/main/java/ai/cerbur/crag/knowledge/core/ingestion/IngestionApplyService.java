package ai.cerbur.crag.knowledge.core.ingestion;

import ai.cerbur.crag.common.annotation.ConstructorInjection;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将一个 {@link IngestionStatusEvent} 应用到 Document 摄取投影（plan_21/21.3）。
 *
 * <p>状态机（{@link IngestionStateMachine}）+ CAS（{@link DocumentDao#applyIngestionProjection}）的组合：
 *
 * <ol>
 *   <li>按事件 tenantId 读取 Document；缺失 → {@link IngestionApplyResult#retryable(String)}（重排可能）；
 *   <li>校验 event.knowledgeBaseId 与文档归属；不符 → {@link IngestionApplyResult#rejected(String)}（DLQ）；
 *   <li>比对 operationVersion：旧版本 {@link IngestionApplyResult#acknowledged(String)}；高版本 {@link
 *       IngestionApplyResult#rejected(String)}（RAG 不应超前 Knowledge）；
 *   <li>状态机判定：ACKNOWLEDGED/REJECTED 不写库，事件仍 ACK；APPLIED 调用 DAO CAS；
 *   <li>CAS 冲突或瞬时异常 → {@link IngestionApplyResult#retryable(String)}（留 Pending）。
 * </ol>
 *
 * <p>事务边界仅包含 DAO CAS 单条写库；不调用 gRPC、Sidecar 或 LLM。事件 payload 已在 handler 层做安全限长。
 */
@Service
@ConstructorInjection
public class IngestionApplyService {

  private static final Logger log = LoggerFactory.getLogger(IngestionApplyService.class);

  private final DocumentDao documentDao;

  public IngestionApplyService(DocumentDao documentDao) {
    this.documentDao = documentDao;
  }

  /**
   * 应用一个状态事件到 Document 投影。
   *
   * @param event 已由 handler 解析并安全限长的事件
   * @return 应用结果，驱动 handler 的 ACK/重试/DLQ 决策
   */
  @Transactional
  public IngestionApplyResult apply(IngestionStatusEvent event) {
    Optional<DocumentEntity> loaded =
        documentDao.findByDocIdAndTenant(event.docId(), event.tenantId());
    if (loaded.isEmpty()) {
      // 文档在声明的 tenant 下不存在：可能是 doc 未建（重排）或 tenant 不一致。
      // docId 由 Snowflake 全局唯一，缺失更可能是 tenant/doc 归属不一致 → 安全 DLQ。
      log.warn(
          "INGESTION_* event references unknown doc under tenant, rejecting — "
              + "tenantId={} kbId={} docId={} opVersion={}",
          event.tenantId(),
          event.knowledgeBaseId(),
          event.docId(),
          event.operationVersion());
      return IngestionApplyResult.rejected("event references unknown doc under declared tenant");
    }

    DocumentEntity doc = loaded.get();
    if (doc.getKnowledgeBaseId() != event.knowledgeBaseId()) {
      log.warn(
          "INGESTION_* event tenant ok but kb mismatch, rejecting — docId={} eventKb={} docKb={}",
          event.docId(),
          event.knowledgeBaseId(),
          doc.getKnowledgeBaseId());
      return IngestionApplyResult.rejected("event knowledgeBaseId does not match document");
    }

    long currentOpVersion = doc.getOperationVersion();
    if (event.operationVersion() < currentOpVersion) {
      log.info(
          "INGESTION_* old operationVersion acknowledged without apply — docId={} eventOp={} docOp={}",
          event.docId(),
          event.operationVersion(),
          currentOpVersion);
      return IngestionApplyResult.acknowledged("old operationVersion, no apply");
    }
    if (event.operationVersion() > currentOpVersion) {
      log.warn(
          "INGESTION_* future operationVersion rejected — docId={} eventOp={} docOp={}",
          event.docId(),
          event.operationVersion(),
          currentOpVersion);
      return IngestionApplyResult.rejected("event operationVersion ahead of document");
    }

    IngestionStatus current = IngestionStatus.fromCode(doc.getIngestionStatus());
    IngestionTransitionDecision decision =
        IngestionStateMachine.decide(current, event.targetStatus());
    if (!decision.shouldApply()) {
      log.info(
          "INGESTION_* event acknowledged without apply — docId={} op={} outcome={} reason={}",
          event.docId(),
          event.operationVersion(),
          decision.outcome(),
          decision.reason());
      return IngestionApplyResult.acknowledged(decision.reason());
    }

    TargetFields target = TargetFields.from(event, doc);
    try {
      documentDao.applyIngestionProjection(
          doc.getDocId(),
          doc.getTenantId(),
          doc.getKnowledgeBaseId(),
          currentOpVersion,
          doc.getVersion(),
          target.status,
          target.attempt,
          target.jobId,
          target.failureCategory,
          target.failureMessage,
          target.startedAt,
          target.completedAt,
          target.nextRetryAt);
      log.info(
          "INGESTION_* projection applied — docId={} op={} status={}",
          event.docId(),
          event.operationVersion(),
          target.status);
      return IngestionApplyResult.applied("projection applied: " + target.status);
    } catch (VersionConflictException e) {
      log.warn(
          "INGESTION_* apply CAS lost, will retry — docId={} op={} reason={}",
          event.docId(),
          event.operationVersion(),
          e.getMessage());
      return IngestionApplyResult.retryable("apply CAS lost to a concurrent writer");
    } catch (RuntimeException e) {
      log.warn(
          "INGESTION_* apply failed transiently, will retry — docId={} op={} reason={}",
          event.docId(),
          event.operationVersion(),
          e.getMessage());
      return IngestionApplyResult.retryable("apply failed transiently");
    }
  }

  /** 计算目标投影字段：从事件取值，缺失或回填默认（attempt、时间字段）。 */
  private record TargetFields(
      String status,
      int attempt,
      Long jobId,
      String failureCategory,
      String failureMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime nextRetryAt) {

    static TargetFields from(IngestionStatusEvent event, DocumentEntity doc) {
      int attempt =
          event.attempt() != null ? event.attempt() : orDefault(doc.getIngestionAttempt());
      return new TargetFields(
          event.targetStatus().name(),
          attempt,
          event.jobId() != null ? event.jobId() : doc.getIngestionJobId(),
          event.targetStatus() == IngestionStatus.FAILED ? event.failureCategory() : null,
          event.targetStatus() == IngestionStatus.FAILED ? event.failureMessage() : null,
          resolveStartedAt(event, doc),
          resolveCompletedAt(event, doc),
          null);
    }

    private static int orDefault(Integer value) {
      return value == null ? 0 : value;
    }

    private static LocalDateTime resolveStartedAt(IngestionStatusEvent event, DocumentEntity doc) {
      if (event.targetStatus() == IngestionStatus.PROCESSING) {
        return event.startedAt() != null ? event.startedAt() : LocalDateTime.now();
      }
      return doc.getStartedAt();
    }

    private static LocalDateTime resolveCompletedAt(
        IngestionStatusEvent event, DocumentEntity doc) {
      if (event.targetStatus() == IngestionStatus.READY
          || event.targetStatus() == IngestionStatus.FAILED) {
        return event.completedAt() != null ? event.completedAt() : LocalDateTime.now();
      }
      return doc.getCompletedAt();
    }
  }
}
