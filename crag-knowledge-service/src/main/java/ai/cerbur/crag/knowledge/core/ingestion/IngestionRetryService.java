package ai.cerbur.crag.knowledge.core.ingestion;

import ai.cerbur.crag.common.annotation.ConstructorInjection;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.metrics.IngestionRecoveryMetrics;
import ai.cerbur.crag.knowledge.producer.DocUploadedOutboxWriter;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge 摄取手动/自动 retry 用例（plan_21/21.5）。
 *
 * <p>事务边界：单条 CAS 写库 + 同事务写 DOC_UPLOADED Outbox，保证 retry 状态推进与新事件原子一致；不调用 gRPC、Sidecar 或 LLM。
 *
 * <p>核心不变量：
 *
 * <ul>
 *   <li>只对 {@code FAILED} 且 {@link RetryPolicy} 允许重试的文档创建新版本；
 *   <li>CAS 递增 operationVersion 与 attempt，并发只允许一个新版本成功；
 *   <li>新版本 status=PENDING、失败字段清空，并由 {@link DocUploadedOutboxWriter} 同事务发布新 DOC_UPLOADED 事件。
 * </ul>
 */
@Service
@ConstructorInjection
public class IngestionRetryService {

  private static final Logger log = LoggerFactory.getLogger(IngestionRetryService.class);

  private final DocumentDao documentDao;
  private final DocUploadedOutboxWriter outboxWriter;
  private final IngestionRecoveryMetrics metrics;
  private final RetryPolicy retryPolicy;

  public IngestionRetryService(
      DocumentDao documentDao,
      DocUploadedOutboxWriter outboxWriter,
      IngestionRecoveryMetrics metrics,
      RetryPolicy retryPolicy) {
    this.documentDao = documentDao;
    this.outboxWriter = outboxWriter;
    this.metrics = metrics;
    this.retryPolicy = retryPolicy;
  }

  /**
   * 对允许重试的 FAILED 摄取创建新版本。
   *
   * <p>自动与手动 retry 共用本方法：自动 retry 由 {@code IngestionReconcileService} 在退避到达后调用；手动 retry 由 Console
   * Document API（21.8）调用。
   *
   * @param actorUserId 发起 retry 的用户 ID（仅日志关联）
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @return retry 后的文档视图（新 operationVersion、PENDING）
   * @throws RetryNotAllowedException 文档非 FAILED、不可重试分类、已达上限或文档不存在/KB 不符
   * @throws VersionConflictException 并发 retry 抢占失败
   */
  @Transactional
  public DocumentResult retry(long actorUserId, long tenantId, long knowledgeBaseId, long docId) {
    Optional<DocumentEntity> loaded = documentDao.findByDocIdAndTenant(docId, tenantId);
    if (loaded.isEmpty()) {
      metrics.retryRejected();
      throw new RetryNotAllowedException("document not found under declared tenant");
    }
    DocumentEntity doc = loaded.get();
    if (doc.getKnowledgeBaseId() != knowledgeBaseId) {
      metrics.retryRejected();
      throw new RetryNotAllowedException("knowledgeBaseId does not match document");
    }
    if (!"FAILED".equals(doc.getIngestionStatus())) {
      metrics.retryRejected();
      throw new RetryNotAllowedException(
          "retry only allowed on FAILED documents, current=" + doc.getIngestionStatus());
    }
    int currentAttempt = currentAttempt(doc);
    RetryDecision decision = retryPolicy.decide(doc.getFailureCategory(), currentAttempt);
    if (!decision.retryable()) {
      metrics.retryRejected();
      throw new RetryNotAllowedException(decision.reason());
    }

    long currentOpVersion = doc.getOperationVersion();
    long currentVersion = currentVersion(doc);
    int newAttempt = currentAttempt + 1;
    long newOperationVersion = currentOpVersion + 1;
    try {
      documentDao.retryIngestion(
          docId,
          tenantId,
          knowledgeBaseId,
          currentOpVersion,
          currentVersion,
          newAttempt,
          newOperationVersion);
    } catch (VersionConflictException e) {
      log.warn(
          "retry CAS lost to a concurrent writer — docId={} op={} version={}",
          docId,
          currentOpVersion,
          currentVersion);
      metrics.retryConflict();
      throw e;
    }
    // CAS 成功后重新读取，确保 Outbox 与最新持久化状态一致。
    DocumentEntity refreshed =
        documentDao
            .findByDocIdAndTenant(docId, tenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "document vanished after retry CAS — docId=" + docId));
    DocumentResult result = DocumentResult.from(refreshed);
    outboxWriter.write(result);
    metrics.retryIssued();
    log.info(
        "retry issued — actorUserId={} docId={} newOp={} newAttempt={}",
        actorUserId,
        docId,
        newOperationVersion,
        newAttempt);
    return result;
  }

  private static int currentAttempt(DocumentEntity doc) {
    Integer attempt = doc.getIngestionAttempt();
    return attempt == null ? 0 : attempt;
  }

  private static long currentVersion(DocumentEntity doc) {
    Long version = doc.getVersion();
    Objects.requireNonNull(version, "document version must not be null");
    return version;
  }
}
