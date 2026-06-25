package ai.cerbur.crag.api.dto.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AdminRagResponse 不可变性单元测试.
 *
 * <p>证明构造后修改原始列表不影响 DTO 内容。
 */
class AdminRagResponseTest {

  @Test
  @DisplayName("modifying original list after construction does not affect DTO")
  void modifyingOriginalListDoesNotAffectDto() {
    List<String> mutable = new ArrayList<>(Arrays.asList("p1", "p2"));
    AdminRagResponse dto = new AdminRagResponse("doc-1", 2, "PENDING", mutable);

    // Mutate the original list
    mutable.add("p3");
    mutable.set(0, "hacked");

    // DTO should be unaffected
    assertEquals(List.of("p1", "p2"), dto.parentChunkIds());
  }

  @Test
  @DisplayName("null parentChunkIds defaults to empty list")
  void nullParentChunkIdsDefaultsToEmptyList() {
    AdminRagResponse dto = new AdminRagResponse("doc-1", 0, "PENDING", null);
    assertEquals(List.of(), dto.parentChunkIds());
  }

  @Test
  @DisplayName("returned list is immutable")
  void returnedListIsImmutable() {
    List<String> input = List.of("p1");
    AdminRagResponse dto = new AdminRagResponse("doc-1", 1, "PENDING", input);

    List<String> result = dto.parentChunkIds();
    assertThrows(UnsupportedOperationException.class, () -> result.add("p2"));
    assertThrows(UnsupportedOperationException.class, () -> result.set(0, "hacked"));
  }
}
