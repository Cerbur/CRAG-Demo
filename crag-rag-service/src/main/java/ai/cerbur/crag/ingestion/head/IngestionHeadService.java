package ai.cerbur.crag.ingestion.head;

import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventTypes;
import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventWriter;
import ai.cerbur.crag.storage.IngestionHeadDao;
import ai.cerbur.crag.storage.IngestionJobConflictException;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import ai.cerbur.crag.storage.result.IngestionHead;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion head 推进服务（Plan 21.4）—— 维护 docId 当前 operationVersion 的单调指针，并协调旧活动 Job 的 SUPERSEDED 标记.
 *
 * <p>核心不变量：
 *
 * <ul>
 *   <li>{@code document_ingestion_head.operation_version} 单调递增；低版本事件直接视为完成（幂等 ACK）；
 *   <li>等版本事件不重复推进（幂等 ACK）；
 *   <li>高版本事件 CAS 抢占：head 成功推进后，同 doc 旧 PENDING / PROCESSING Job 标记 SUPERSEDED， 使迟到 Worker 无法 READY
 *       一个已被取代的版本（{@code IngestionJobRepository.tryMarkReady} 在 SQL 层校验 head）；
 *   <li>该服务不负责 retry/Reconciler（21.5）；只负责 head 单调推进与旧 Job 取代的原子编排.
 * </ul>
 *
 * @since 2026-06-28
 */
@Service
public class IngestionHeadService {

  private static final Logger log = LoggerFactory.getLogger(IngestionHeadService.class);

  @Autowired private IngestionHeadDao ingestionHeadDao;
  @Autowired private IngestionJobDao ingestionJobDao;
  @Autowired private RagIngestionStatusEventWriter statusEventWriter;

  /**
   * 在 DOC_UPLOADED resolve 前单调推进 head，返回推进结果与是否需要继续处理.
   *
   * <p>调用方按 {@link HeadAdvanceResult#shouldProcess()} 决定是否继续 Job 编排：旧版本或等版本事件视为已处理，不重复创建/推进
   * Job；新版本事件 head 推进成功后继续处理.
   *
   * @param tenantId 租户 ID（仅日志关联，不参与 head 决策）
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 本次事件携带的 operationVersion
   * @return head 推进结果，含当前 head 与是否需要继续处理
   */
  @Transactional
  public HeadAdvanceResult advance(
      long tenantId, long knowledgeBaseId, long docId, long operationVersion) {
    IngestionHead current = ingestionHeadDao.findOrCreate(knowledgeBaseId, docId, operationVersion);
    if (operationVersion < current.operationVersion()) {
      log.info(
          "Ingestion head advance ACK low-version — docId={} event={} head={}",
          docId,
          operationVersion,
          current.operationVersion());
      return new HeadAdvanceResult(current, false, HeadAdvanceOutcome.LOW_VERSION_ACK);
    }
    if (operationVersion == current.operationVersion()) {
      log.info(
          "Ingestion head advance ACK equal-version — docId={} version={}",
          docId,
          operationVersion);
      return new HeadAdvanceResult(current, true, HeadAdvanceOutcome.EQUAL_VERSION_ACK);
    }
    int affected = ingestionHeadDao.advance(current, operationVersion);
    if (affected == 0) {
      // 并发下另一实例已推进到等或更高版本，重新读取并按当前最大版本决策。
      IngestionHead refreshed =
          ingestionHeadDao
              .findByDocId(docId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "ingestion head vanished after advance attempt — docId=" + docId));
      if (refreshed.operationVersion() == operationVersion) {
        log.info(
            "Ingestion head advance concurrent equal — docId={} version={}",
            docId,
            operationVersion);
        return new HeadAdvanceResult(refreshed, true, HeadAdvanceOutcome.EQUAL_VERSION_ACK);
      }
      log.info(
          "Ingestion head advance ACK concurrent higher — docId={} event={} head={}",
          docId,
          operationVersion,
          refreshed.operationVersion());
      return new HeadAdvanceResult(refreshed, false, HeadAdvanceOutcome.LOW_VERSION_ACK);
    }
    // head 推进成功：把同 doc 旧 PENDING / PROCESSING Job 标记 SUPERSEDED。
    int superseded = ingestionJobDao.markSuperseded(docId, operationVersion);
    IngestionHead advanced =
        new IngestionHead(knowledgeBaseId, docId, operationVersion, current.version() + 1);
    log.info(
        "Ingestion head advanced — docId={} version={} supersededJobs={}",
        docId,
        operationVersion,
        superseded);
    return new HeadAdvanceResult(advanced, true, HeadAdvanceOutcome.ADVANCED);
  }

  /**
   * 查询当前 head 投影（供 Provider/Reconciler 读取）.
   *
   * @param tenantId 租户 ID（不参与 head 查询，仅调用方一致性）
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @return head 投影；docId 不存在时为 empty
   */
  public Optional<IngestionHead> get(long tenantId, long knowledgeBaseId, long docId) {
    return ingestionHeadDao.findByKnowledgeBaseIdAndDocId(knowledgeBaseId, docId);
  }

  /**
   * 查询当前 (docId, operationVersion) 的权威 Job 状态投影（Plan 21.4，供 IngestionStatus Provider）.
   *
   * <p>仅当 head 存在且指向该版本、对应 Job 也存在时返回投影；否则 empty（Provider 映射 NOT_FOUND）.
   *
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @return Job 状态投影，或 empty
   */
  public Optional<IngestionStatusResult> currentJobStatus(
      long knowledgeBaseId, long docId, long operationVersion) {
    Optional<IngestionHead> head =
        ingestionHeadDao.findByKnowledgeBaseIdAndDocId(knowledgeBaseId, docId);
    if (head.isEmpty()) {
      return Optional.empty();
    }
    Optional<IngestionJob> job =
        ingestionJobDao.findByDocIdAndOperationVersion(docId, operationVersion);
    return job.map(IngestionStatusResult::from);
  }

  /**
   * 推进滞留 PROCESSING Job 为 FAILED 超时终态（Plan 21.4，供 21.5 Reconciler 消费）.
   *
   * <p>先按 (docId, operationVersion) 读取 Job，再以 status/version/startedAt CAS 终态化。Job 非
   * PROCESSING、版本已变 或 started_at 不早于 staleBefore 时为 no-op（抛 {@link IngestionJobConflictException}
   * 由调用方按业务语义处理）.
   *
   * @param tenantId 租户 ID（仅日志关联）
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param staleBefore 视为滞留的 started_at 上界
   * @return 终态化后的状态投影，或 empty 表示 Job 不存在
   */
  @Transactional
  public Optional<IngestionStatusResult> markTimedOut(
      long tenantId,
      long knowledgeBaseId,
      long docId,
      long operationVersion,
      LocalDateTime staleBefore) {
    Optional<IngestionJob> job =
        ingestionJobDao.findByDocIdAndOperationVersion(docId, operationVersion);
    if (job.isEmpty()) {
      return Optional.empty();
    }
    IngestionJob j = job.get();
    if (j.getStatus() != IngestionJobStatus.PROCESSING) {
      return Optional.of(IngestionStatusResult.from(j));
    }
    try {
      String failureCategory = "PROCESSING_TIMEOUT";
      String failureMessage = "ingestion job exceeded processing budget";
      ingestionJobDao.markTimedOut(
          j.getDocId(),
          j.getOperationVersion(),
          j.getVersion(),
          staleBefore,
          LocalDateTime.now(),
          failureCategory,
          failureMessage);
      j.setStatus(IngestionJobStatus.FAILED);
      j.setFailureCategory(failureCategory);
      j.setFailureMessage(failureMessage);
      // 状态事件由 producer 写入本地 outbox；超时终态化也发布 FAILED 事件供 Knowledge 收敛（21.5 消费）。
      statusEventWriter.write(
          j, RagIngestionStatusEventTypes.INGESTION_FAILED, failureCategory, failureMessage);
      return Optional.of(IngestionStatusResult.from(j));
    } catch (IngestionJobConflictException e) {
      log.info(
          "markTimedOut conflict, job already advanced — docId={} operationVersion={}",
          docId,
          operationVersion);
      return Optional.empty();
    }
  }
}
