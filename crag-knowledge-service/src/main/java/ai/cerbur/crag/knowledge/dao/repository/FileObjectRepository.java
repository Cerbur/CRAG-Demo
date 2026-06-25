package ai.cerbur.crag.knowledge.dao.repository;

import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * FileObject Spring Data JPA Repository，仅允许 {@code ai.cerbur.crag.knowledge.dao} 包调用。
 *
 * <p>FileObject 通过 {@code docId} 访问；租户隔离由 Document 的 tenant 校验保证。
 */
@Repository
public interface FileObjectRepository extends JpaRepository<FileObjectEntity, Long> {

  Optional<FileObjectEntity> findByDocId(long docId);
}
