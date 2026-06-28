package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.entity.DocumentIngestionHead;
import ai.cerbur.crag.storage.repository.DocumentIngestionHeadRepository;
import ai.cerbur.crag.storage.result.IngestionHead;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Ingestion Head DAO（Plan 21.4）—— 按 docId 维护当前 operationVersion 的单调指针.
 *
 * <p>职责：
 *
 * <ul>
 *   <li>{@link #findOrCreate} 幂等创建或返回已有 head（首个 operationVersion 为初始值）；
 *   <li>{@link #findByDocId} / {@link #findByKnowledgeBaseIdAndDocId} 读取当前指针；
 *   <li>{@link #advance} CAS 单调推进 operationVersion（严格大于当前值），低版本/同版本幂等 ACK， 高版本成功推进。affected == 0
 *       不抛异常，由调用方依据返回结果按业务语义处理。
 * </ul>
 *
 * <p>Repository 只负责单表持久化与 CAS 更新声明；CAS 判定与并发回退在此层完成。事务边界由调用方 Service 层声明.
 *
 * @since 2026-06-28
 */
@Component
public class IngestionHeadDao {

  private static final Logger log = LoggerFactory.getLogger(IngestionHeadDao.class);

  @Autowired private DocumentIngestionHeadRepository headRepository;

  /**
   * 幂等创建或返回已有 head.
   *
   * <p>首次见到 {@code docId} 时按 {@code initialOperationVersion} 创建 head 行；重复命中唯一主键时回退查询返回已有
   * head（可能持有等于或更高的 operationVersion）。{@code initialOperationVersion} 由调用方传入新版本摄取即将使用的
   * operationVersion（Knowledge retry 递增后的新值），保证 head 创建即为该版本；同 docId 并发创建按 DB 主键收敛.
   *
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param initialOperationVersion 初始 operationVersion
   * @return head 投影
   */
  public IngestionHead findOrCreate(
      long knowledgeBaseId, long docId, long initialOperationVersion) {
    Optional<DocumentIngestionHead> existing = headRepository.findByDocId(docId);
    if (existing.isPresent()) {
      return toResult(existing.get());
    }
    DocumentIngestionHead head = new DocumentIngestionHead();
    head.setKnowledgeBaseId(knowledgeBaseId);
    head.setDocId(docId);
    head.setOperationVersion(initialOperationVersion);
    head.setVersion(0);
    head.setUpdatedAt(LocalDateTime.now());
    try {
      DocumentIngestionHead saved = headRepository.save(head);
      return toResult(saved);
    } catch (DataIntegrityViolationException e) {
      DocumentIngestionHead concurrent =
          headRepository
              .findByDocId(docId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "document_ingestion_head unique constraint fired but row not found for docId="
                              + docId,
                          e));
      log.warn("Concurrent ingestion head create resolved to existing — docId={}", docId);
      return toResult(concurrent);
    }
  }

  /** 按 docId 查询 head 投影. */
  public Optional<IngestionHead> findByDocId(long docId) {
    return headRepository.findByDocId(docId).map(IngestionHeadDao::toResult);
  }

  /** 按 KB + docId 查询 head 投影. */
  public Optional<IngestionHead> findByKnowledgeBaseIdAndDocId(long knowledgeBaseId, long docId) {
    return headRepository
        .findByDocId(docId)
        .filter(h -> h.getKnowledgeBaseId() == knowledgeBaseId)
        .map(IngestionHeadDao::toResult);
  }

  /**
   * CAS 单调推进 head operationVersion.
   *
   * <p>调用方必须先读取 head 再调用此方法。仅当 {@code newOperationVersion > head.operationVersion} 且 version 匹配时
   * 推进成功。affected == 0 表示：新版本不高于当前（幂等场景，调用方视为 ACK），或 version 已变（并发抢占失败， 调用方应重新读取并按当前最大版本决策）.
   *
   * @param current 当前读取的 head 投影
   * @param newOperationVersion 新的 operationVersion，必须严格大于当前
   * @return affected rows（1 = 推进成功，0 = 版本不更高或 version 已变）
   */
  public int advance(IngestionHead current, long newOperationVersion) {
    return headRepository.tryAdvance(
        current.docId(), newOperationVersion, (int) current.version(), LocalDateTime.now());
  }

  private static IngestionHead toResult(DocumentIngestionHead head) {
    return new IngestionHead(
        head.getKnowledgeBaseId(),
        head.getDocId(),
        head.getOperationVersion(),
        head.getVersion() == null ? 0L : head.getVersion().longValue());
  }
}
