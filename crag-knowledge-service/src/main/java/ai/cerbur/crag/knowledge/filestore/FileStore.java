package ai.cerbur.crag.knowledge.filestore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储抽象：临时写入、原子落盘、读取与删除。
 *
 * <p>接口形式允许上层（{@code core.document.DocumentUploadService}）在纯单元测试中使用内存替身，真实本地实现为 {@link
 * LocalFileStore}。
 */
public interface FileStore {

  /** 开启一个新的临时写入槽。 */
  TempFileSink openTempSink() throws IOException;

  /** 将临时文件原子移动到 storage key 对应的最终位置。 */
  void commit(Path tempPath, String storageKey) throws IOException;

  /** 静默删除 storage key 对应的最终文件，用于事务失败回滚。 */
  void deleteQuietly(String storageKey);

  /** 打开 storage key 对应的最终文件读取流。 */
  InputStream openRead(String storageKey) throws IOException;
}
