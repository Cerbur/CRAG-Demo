package ai.cerbur.crag.smoke.ingestion;

import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * router2 smoke 诊断服务（Plan 19）：仅 smoke Profile 启用，读取 RAG ingestion 状态、状态事件与 chunk 计数， 供 Docker HTTP
 * 回归脚本轮询 Job 是否 READY、观察状态事件、校验幂等不重复建 chunk.
 *
 * <p>不修改任何状态，只读 outbox_event / ingestion_job / chunk 表.
 */
@Component
@Profile("smoke")
public class RagIngestionSmokeService {

  @Autowired private IngestionJobDao ingestionJobDao;
  @Autowired private ChunkDao chunkDao;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 查询 Job 状态（按 KB + docId），不存在返回 empty. */
  public Optional<IngestionJob> findJob(long knowledgeBaseId, long docId) {
    return ingestionJobDao.findByKnowledgeBaseIdAndDocId(knowledgeBaseId, docId);
  }

  /** 查询某文档的 RAG ingestion 状态事件（outbox_event 中 event_type 以 INGESTION_ 开头）. */
  public List<IngestionEventStatus> findStatusEvents(long docId) {
    return jdbcTemplate.query(
        "SELECT event_id, event_type, status FROM outbox_event"
            + " WHERE resource_id = ? AND event_type LIKE 'INGESTION\\_%' ESCAPE '\\' ORDER BY event_id",
        (rs, i) ->
            new IngestionEventStatus(
                rs.getString("event_id"), rs.getString("event_type"), rs.getString("status")),
        docId);
  }

  /** 统计某文档的 chunk 行数（幂等校验：重复 DOC_UPLOADED 不应增加该数）. */
  public long countChunksByDocId(long docId) {
    return chunkDao.countByDocId(docId);
  }

  /** ingestion 状态事件摘要. */
  public record IngestionEventStatus(String eventId, String eventType, String status) {}
}
