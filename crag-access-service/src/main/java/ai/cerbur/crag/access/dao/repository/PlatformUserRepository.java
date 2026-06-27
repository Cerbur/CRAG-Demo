package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** platform_user Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。主键即 user_id。 */
@Repository
public interface PlatformUserRepository extends JpaRepository<PlatformUserEntity, Long> {}
