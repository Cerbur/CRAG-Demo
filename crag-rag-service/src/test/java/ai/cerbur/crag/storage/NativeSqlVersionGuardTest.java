package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.storage.repository.ChunkEmbeddingRepository;
import ai.cerbur.crag.storage.repository.ChunkFtsRepository;
import ai.cerbur.crag.storage.repository.ChunkRepository;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Native SQL 版本防线静态校验（Plan 21.4）—— 通过反射读取 Repository {@code @Query} 注解的 SQL 文本，断言每条召回 native SQL 都先
 * JOIN {@code document_ingestion_head} 与 READY {@code ingestion_job}，并按 {@code operation_version}
 * 限定召回.
 *
 * <p>H2 不支持 pgvector / tsvector，无法在组件测试中执行 Sparse / Dense native SQL；真实执行与排序由 Docker 回归证明.
 * 此测试用静态断言保证 SQL 文本中版本防线条件存在，避免回归丢失 JOIN.
 */
@DisplayName("Native SQL 版本防线：head + READY ingestion_job JOIN")
class NativeSqlVersionGuardTest {

  private static String queryValue(Class<?> repoType, String methodName, Class<?>... paramTypes)
      throws NoSuchMethodException {
    Method method = repoType.getDeclaredMethod(methodName, paramTypes);
    Query query = method.getAnnotation(Query.class);
    assertThat(query).as("@Query on %s#%s", repoType.getSimpleName(), methodName).isNotNull();
    return query.value();
  }

  @Test
  @DisplayName(
      "Dense searchSimilar：JOIN document_ingestion_head + READY ingestion_job，按 operation_version 限定")
  void denseSearchSimilarJoinsHeadAndReadyJob() throws Exception {
    String sql =
        queryValue(
            ChunkEmbeddingRepository.class, "searchSimilar", long.class, String.class, int.class);
    assertThat(sql).contains("document_ingestion_head");
    assertThat(sql).contains("ingestion_job");
    assertThat(sql).contains("j.status = 2");
    assertThat(sql).contains("ce.operation_version = h.operation_version");
    assertThat(sql).contains("c.operation_version = h.operation_version");
    // 列顺序：[chunk_id, parent_chunk_id, chunk_index, score, content]，按 SELECT 出现顺序断言
    assertThat(sql.indexOf("c.chunk_id")).isLessThan(sql.indexOf("c.parent_chunk_id"));
    assertThat(sql.indexOf("c.parent_chunk_id")).isLessThan(sql.indexOf("c.chunk_index"));
    assertThat(sql.indexOf("c.chunk_index")).isLessThan(sql.indexOf("AS score"));
    assertThat(sql.indexOf("AS score")).isLessThan(sql.indexOf("c.content"));
  }

  @Test
  @DisplayName(
      "Sparse searchFts：JOIN document_ingestion_head + READY ingestion_job，按 operation_version 限定")
  void sparseSearchFtsJoinsHeadAndReadyJob() throws Exception {
    String sql =
        queryValue(ChunkFtsRepository.class, "searchFts", long.class, String.class, int.class);
    assertThat(sql).contains("document_ingestion_head");
    assertThat(sql).contains("ingestion_job");
    assertThat(sql).contains("j.status = 2");
    assertThat(sql).contains("cf.operation_version = h.operation_version");
    assertThat(sql).contains("c.operation_version = h.operation_version");
    // 列顺序：[chunk_id, parent_chunk_id, chunk_index, score, content]，按 SELECT 出现顺序断言
    assertThat(sql.indexOf("c.chunk_id")).isLessThan(sql.indexOf("c.parent_chunk_id"));
    assertThat(sql.indexOf("c.parent_chunk_id")).isLessThan(sql.indexOf("c.chunk_index"));
    assertThat(sql.indexOf("c.chunk_index")).isLessThan(sql.indexOf("ts_rank"));
    assertThat(sql.indexOf("ts_rank")).isLessThan(sql.indexOf("c.content"));
  }

  @Test
  @DisplayName(
      "Parent findParentContentsByIds：JOIN head + READY ingestion_job，返回 chunk_id/doc_id/content")
  void parentContentsByIdsJoinsHeadAndReadyJob() throws Exception {
    String sql =
        queryValue(
            ChunkRepository.class, "findParentContentsByIds", long.class, java.util.List.class);
    assertThat(sql).contains("document_ingestion_head");
    assertThat(sql).contains("ingestion_job");
    assertThat(sql).contains("j.status = 2");
    assertThat(sql).contains("c.operation_version = h.operation_version");
    // 列顺序：[chunk_id, doc_id, content]，按 SELECT 出现顺序断言
    assertThat(sql.indexOf("c.chunk_id")).isLessThan(sql.indexOf("c.doc_id"));
    assertThat(sql.indexOf("c.doc_id")).isLessThan(sql.indexOf("c.content"));
  }

  @Test
  @DisplayName("Dense insert：写入 operation_version 列")
  void denseInsertWritesOperationVersion() throws Exception {
    String sql =
        queryValue(
            ChunkEmbeddingRepository.class,
            "insert",
            long.class,
            long.class,
            long.class,
            String.class);
    assertThat(sql).contains("operation_version");
  }

  @Test
  @DisplayName("Sparse insert：写入 operation_version 列")
  void sparseInsertWritesOperationVersion() throws Exception {
    String sql =
        queryValue(
            ChunkFtsRepository.class, "insert", long.class, long.class, long.class, String.class);
    assertThat(sql).contains("operation_version");
  }
}
