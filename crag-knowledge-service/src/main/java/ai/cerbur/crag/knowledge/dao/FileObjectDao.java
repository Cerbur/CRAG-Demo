package ai.cerbur.crag.knowledge.dao;

import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import ai.cerbur.crag.knowledge.dao.repository.FileObjectRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * FileObject 数据库访问边界，只依赖 {@link FileObjectRepository}。
 *
 * <p>FileObject 通过 {@code docId} 访问；{@code storageKey} 是 Knowledge 内部字段，不得向跨服务契约、HTTP DTO 或日志泄漏。
 */
@Component
public class FileObjectDao {

  @Autowired private FileObjectRepository fileObjectRepository;

  /** 插入文件对象；ID 由数据库 identity 列生成并回填。 */
  public FileObjectEntity insert(FileObjectEntity entity) {
    return fileObjectRepository.save(entity);
  }

  /** 按文档 ID 查询文件对象。 */
  public Optional<FileObjectEntity> findByDocId(long docId) {
    return fileObjectRepository.findByDocId(docId);
  }
}
