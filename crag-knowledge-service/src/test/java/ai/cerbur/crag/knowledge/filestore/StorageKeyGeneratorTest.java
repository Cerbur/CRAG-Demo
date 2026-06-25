package ai.cerbur.crag.knowledge.filestore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StorageKeyGenerator")
class StorageKeyGeneratorTest {

  private final StorageKeyGenerator generator = new StorageKeyGenerator();

  @Test
  @DisplayName("key 包含租户与知识库，且不包含原始文件名")
  void keyCarriesTenantAndKbButNotFilename() {
    String key = generator.generate(123L, 456L);

    assertThat(key).startsWith("123/456/");
    assertThat(key.split("/")).hasSize(3);
    assertThat(key).doesNotContain("secret.txt");
  }

  @Test
  @DisplayName("连续生成的 key 互不相同")
  void keysAreUnique() {
    assertThat(generator.generate(1L, 1L)).isNotEqualTo(generator.generate(1L, 1L));
  }
}
