package ai.cerbur.crag.knowledge.filestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LocalFileStore 组件测试：用真实临时目录验证临时写入、原子落盘、读取与删除的端到端行为，以及 storage key 路径遍历防护。 */
@DisplayName("LocalFileStore")
class FileStoreComponentTest {

  @TempDir Path root;

  private LocalFileStore fileStore;

  @BeforeEach
  void setUp() {
    fileStore = new LocalFileStore(root.toString());
  }

  @Test
  @DisplayName("写入 → 落盘 → 读回保持内容一致，删除后不可读")
  void commitReadDeleteRoundTrip() throws IOException {
    byte[] content = "knowledge file body".getBytes(StandardCharsets.UTF_8);
    TempFileSink sink = fileStore.openTempSink();
    sink.write(content, 0, content.length);
    CompletedUpload completed = sink.finish();
    assertThat(completed.sizeBytes()).isEqualTo(content.length);

    String storageKey = "1/2/abc";
    fileStore.commit(completed.tempPath(), storageKey);

    try (InputStream in = fileStore.openRead(storageKey)) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
    assertThat(Files.exists(root.resolve(storageKey))).isTrue();

    fileStore.deleteQuietly(storageKey);
    assertThat(Files.exists(root.resolve(storageKey))).isFalse();
  }

  @Test
  @DisplayName("escape root 的 storage key 被拒绝")
  void rejectsTraversalStorageKey() throws IOException {
    TempFileSink sink = fileStore.openTempSink();
    sink.write(new byte[] {1}, 0, 1);
    CompletedUpload completed = sink.finish();

    assertThatThrownBy(() -> fileStore.commit(completed.tempPath(), "../../escape"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
