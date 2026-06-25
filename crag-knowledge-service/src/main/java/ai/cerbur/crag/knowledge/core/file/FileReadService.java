package ai.cerbur.crag.knowledge.core.file;

import ai.cerbur.crag.knowledge.core.document.FileType;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.FileObjectDao;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import ai.cerbur.crag.knowledge.filestore.FileStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件读取用例：按文档与租户定位 FileObject 并打开内容读取流。
 *
 * <p>跨租户查询按 not found 处理；返回的 {@link FileRead} 只携带安全 metadata 与内容流，不含 storage key 或路径。调用方负责关闭内容流。
 */
@Service
public class FileReadService {

  @Autowired private DocumentDao documentDao;
  @Autowired private FileObjectDao fileObjectDao;
  @Autowired private FileStore fileStore;

  /** 打开文档文件读取；文档不存在或跨租户时返回空。 */
  @Transactional(readOnly = true)
  public Optional<FileRead> open(long docId, long tenantId) {
    Optional<DocumentEntity> doc = documentDao.findByDocIdAndTenant(docId, tenantId);
    if (doc.isEmpty()) {
      return Optional.empty();
    }
    FileObjectEntity file = fileObjectDao.findByDocId(docId).orElseThrow();
    InputStream content;
    try {
      content = fileStore.openRead(file.getStorageKey());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to open document file", e);
    }
    return Optional.of(
        new FileRead(
            doc.get().getDocId(),
            doc.get().getTenantId(),
            doc.get().getKnowledgeBaseId(),
            FileType.fromDeclared(doc.get().getFileType()),
            file.getSizeBytes(),
            file.getSha256(),
            content));
  }
}
