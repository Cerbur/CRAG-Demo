package ai.cerbur.crag.knowledge.filestore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地文件系统 {@link FileStore} 实现。
 *
 * <p>storage key 解析后必须落在 root 之内（路径遍历防护）；commit 使用原子移动。构造器注入 root 路径，便于在临时目录下直接构造做组件测试。
 */
@Component
public class LocalFileStore implements FileStore {

  private final Path root;

  public LocalFileStore(
      @Value("${crag.knowledge.filestore.root:./build/knowledge-files}") String root) {
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  @Override
  public TempFileSink openTempSink() throws IOException {
    Files.createDirectories(root);
    Path temp = Files.createTempFile(root, "upload-", ".tmp");
    return new TempFileSink(temp);
  }

  @Override
  public void commit(Path tempPath, String storageKey) throws IOException {
    Path target = resolveWithinRoot(storageKey);
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.move(
        tempPath, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public void deleteQuietly(String storageKey) {
    try {
      Files.deleteIfExists(resolveWithinRoot(storageKey));
    } catch (IOException ignored) {
      // best-effort delete; orphaned files handled by future reconciler
    }
  }

  @Override
  public InputStream openRead(String storageKey) throws IOException {
    return Files.newInputStream(resolveWithinRoot(storageKey));
  }

  private Path resolveWithinRoot(String storageKey) {
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("storageKey escapes filestore root: " + storageKey);
    }
    return target;
  }
}
