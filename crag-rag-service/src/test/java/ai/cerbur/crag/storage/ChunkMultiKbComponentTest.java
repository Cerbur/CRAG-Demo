package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 多 KB 写入与索引完成检测组件测试（Plan 19）.
 *
 * <p>H2 下验证：chunk 写入持久化 knowledgeBaseId；{@link ChunkDao#countByDocIdNotFullyIndexed} 正确反映
 * Dense+Sparse 索引完成情况 （parent SKIPPED 不计入；child 仅在 dense 与 sparse 均到终态时才视为完成）。真实 pgvector / FTS 写入由
 * Docker 回归证明（plan_19.7）.
 */
@SpringBootTest(classes = IngestionJobDaoTestConfig.class)
@Transactional
@DisplayName("多 KB chunk 写入与索引完成检测")
class ChunkMultiKbComponentTest {

  @Autowired private ChunkDao chunkDao;

  @Test
  @DisplayName("写入的 chunk 持久化 knowledgeBaseId")
  void chunkPersistsKnowledgeBaseId() {
    Chunk child = Chunk.createChild(11L, 555L, 700L, 1L, "content", 5, 0, "{}");
    chunkDao.saveAll(List.of(child));

    Chunk stored = chunkDao.findByChunkId(11L);

    assertThat(stored).isNotNull();
    assertThat(stored.getKnowledgeBaseId()).isEqualTo(555L);
  }

  @Test
  @DisplayName("INIT child 计入未完成；dense+sparse 均 SUCCESS 的 child 不计入；parent SKIPPED 不计入")
  void countByDocIdNotFullyIndexedReflectsIndexingState() {
    long docA = 701L;
    long kbA = 601L;
    // parent（SKIPPED）+ 2 个 INIT child
    Chunk parentA = Chunk.createParent(21L, kbA, docA, "p", 10, 0, "{}");
    Chunk childA1 = Chunk.createChild(22L, kbA, docA, 21L, "c1", 5, 0, "{}");
    Chunk childA2 = Chunk.createChild(23L, kbA, docA, 21L, "c2", 5, 1, "{}");
    chunkDao.saveAll(List.of(parentA, childA1, childA2));

    assertThat(chunkDao.countByDocIdNotFullyIndexed(docA)).isEqualTo(2);
  }

  @Test
  @DisplayName("child dense+sparse 均到终态 → 未完成计数为 0")
  void fullyIndexedChildCountsZero() {
    long docB = 702L;
    long kbB = 602L;
    Chunk parentB = Chunk.createParent(31L, kbB, docB, "p", 10, 0, "{}");
    Chunk childB = Chunk.createChild(32L, kbB, docB, 31L, "c", 5, 0, "{}");
    childB.setDenseStatus(ChunkStatus.SUCCESS);
    childB.setSparseStatus(ChunkStatus.SUCCESS);
    chunkDao.saveAll(List.of(parentB, childB));

    assertThat(chunkDao.countByDocIdNotFullyIndexed(docB)).isZero();
  }

  @Test
  @DisplayName("child 仅 dense 完成、sparse 未完成 → 仍计入未完成")
  void partiallyIndexedChildStillCounts() {
    long docC = 703L;
    long kbC = 603L;
    Chunk childC = Chunk.createChild(42L, kbC, docC, 41L, "c", 5, 0, "{}");
    childC.setDenseStatus(ChunkStatus.SUCCESS);
    // sparse 仍 INIT
    chunkDao.saveAll(List.of(childC));

    assertThat(chunkDao.countByDocIdNotFullyIndexed(docC)).isEqualTo(1);
  }
}
