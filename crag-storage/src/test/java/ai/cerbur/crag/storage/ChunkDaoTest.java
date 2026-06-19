package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.storage.entity.ChunkStatus;
import ai.cerbur.crag.storage.repository.ChunkRepository;
import ai.cerbur.crag.storage.result.ParentChunkContent;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * ChunkDao 单元测试 —— 验证 CAS 终态更新的 affected row 检查，affected == 0 时抛出 DuplicateKeyException.
 *
 * <p>Repository 层通过 Mockito 隔离，聚焦 Dao 层的业务判断：版本冲突 → 异常.
 *
 * @since 2026-06-17
 */
@DisplayName("ChunkDao CAS 终态更新")
@ExtendWith(MockitoExtension.class)
class ChunkDaoTest {

  @Mock private ChunkRepository chunkRepository;

  @InjectMocks private ChunkDao chunkDao;

  @Nested
  @DisplayName("updateDenseStatus 版本冲突检测")
  class UpdateDenseStatus {

    @Test
    @DisplayName("affected > 0 → 正常返回 affected 值")
    void affectedPositiveReturnsValue() {
      when(chunkRepository.updateDenseStatus("chunk-001", ChunkStatus.SUCCESS, 3)).thenReturn(1);

      int result = chunkDao.updateDenseStatus("chunk-001", ChunkStatus.SUCCESS, 3);

      assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("affected == 0 → 抛出 DuplicateKeyException")
    void affectedZeroThrowsDuplicateKeyException() {
      when(chunkRepository.updateDenseStatus("chunk-001", ChunkStatus.SUCCESS, 3)).thenReturn(0);

      assertThatThrownBy(() -> chunkDao.updateDenseStatus("chunk-001", ChunkStatus.SUCCESS, 3))
          .isInstanceOf(DuplicateKeyException.class)
          .hasMessageContaining("chunk-001")
          .hasMessageContaining("version 3")
          .hasMessageContaining("stale");
    }
  }

  @Nested
  @DisplayName("updateSparseStatus 版本冲突检测")
  class UpdateSparseStatus {

    @Test
    @DisplayName("affected > 0 → 正常返回 affected 值")
    void affectedPositiveReturnsValue() {
      when(chunkRepository.updateSparseStatus("chunk-002", ChunkStatus.FAILED, 5)).thenReturn(1);

      int result = chunkDao.updateSparseStatus("chunk-002", ChunkStatus.FAILED, 5);

      assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("affected == 0 → 抛出 DuplicateKeyException")
    void affectedZeroThrowsDuplicateKeyException() {
      when(chunkRepository.updateSparseStatus("chunk-002", ChunkStatus.FAILED, 5)).thenReturn(0);

      assertThatThrownBy(() -> chunkDao.updateSparseStatus("chunk-002", ChunkStatus.FAILED, 5))
          .isInstanceOf(DuplicateKeyException.class)
          .hasMessageContaining("chunk-002")
          .hasMessageContaining("version 5")
          .hasMessageContaining("stale");
    }
  }

  @Nested
  @DisplayName("findParentContentsByIds")
  class FindParentContentsByIds {

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInputReturnsEmptyList() {
      assertThat(chunkDao.findParentContentsByIds(null)).isEmpty();
      assertThat(chunkDao.findParentContentsByIds(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("正常委托到 repository")
    void delegatesToRepository() {
      List<String> ids = List.of("p1", "p2");
      List<ParentChunkContent> expected =
          List.of(
              new ParentChunkContent("p1", "content 1"), new ParentChunkContent("p2", "content 2"));
      when(chunkRepository.findParentContentsByIds(ids)).thenReturn(expected);

      List<ParentChunkContent> result = chunkDao.findParentContentsByIds(ids);

      assertThat(result).isEqualTo(expected);
      assertThat(result).hasSize(2);
    }
  }
}
