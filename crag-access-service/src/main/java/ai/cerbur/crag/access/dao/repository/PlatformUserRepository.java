package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * platform_user Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。主键即 user_id。
 *
 * <p>{@code findAllByIdIn} 支持按 user_id 集合批量加载 nickname 投影（plan_21/21.2），避免 Membership 列表逐行查询 User。
 */
@Repository
public interface PlatformUserRepository extends JpaRepository<PlatformUserEntity, Long> {

  /** 按 user_id 集合批量加载，供 Membership 列表一次性补齐 nickname。 */
  @Query("SELECT u FROM PlatformUserEntity u WHERE u.userId IN :userIds")
  List<PlatformUserEntity> findAllByIdIn(@Param("userIds") Collection<Long> userIds);
}
