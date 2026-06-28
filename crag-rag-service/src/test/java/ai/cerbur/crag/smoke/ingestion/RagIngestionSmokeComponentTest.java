package ai.cerbur.crag.smoke.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.IngestionJobDaoTestConfig;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * router2 RAG ingestion smoke 诊断组件测试（Plan 19）：H2 下验证 Job 状态查询与 chunk 计数。outbox_event 状态事件查询由 Docker
 * HTTP 回归证明（plan_19.7）.
 */
@SpringBootTest(
    classes = IngestionJobDaoTestConfig.class,
    properties = "spring.profiles.active=smoke")
@Import(RagIngestionSmokeService.class)
@Transactional
@DisplayName("router2 RAG ingestion smoke 诊断")
class RagIngestionSmokeComponentTest {

  @Autowired private RagIngestionSmokeService service;
  @Autowired private ChunkDao chunkDao;
  @Autowired private IngestionJobDao ingestionJobDao;

  @Test
  @DisplayName("findJob 返回已创建的 Job；chunk 计数反映写入的 chunk 行数")
  void jobLookupAndChunkCount() {
    long kb = 4321L;
    long doc = 7007L;
    chunkDao.saveAll(
        List.of(
            Chunk.createParent(61L, kb, doc, 1L, "p", 10, 0, "{}"),
            Chunk.createChild(62L, kb, doc, 1L, 61L, "c", 5, 0, "{}")));

    assertThat(service.countChunksByDocId(doc)).isEqualTo(2);
    assertThat(service.findJob(kb, doc)).isEmpty();

    ingestionJobDao.findOrCreate(7L, kb, doc, 1L, "TXT", 2L, "x");

    assertThat(service.findJob(kb, doc)).isPresent();
  }

  @Test
  @DisplayName("IngestionJobStatus 枚举覆盖 router2 全部状态")
  void statusEnumCoversRouter2States() {
    assertThat(IngestionJobStatus.PENDING.name()).isEqualTo("PENDING");
    assertThat(IngestionJobStatus.PROCESSING.name()).isEqualTo("PROCESSING");
    assertThat(IngestionJobStatus.READY.name()).isEqualTo("READY");
    assertThat(IngestionJobStatus.FAILED.name()).isEqualTo("FAILED");
  }
}
