package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.dao.repository.PlatformUserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** platform_user 数据库访问边界，只依赖 {@link PlatformUserRepository}。 */
@Component
public class PlatformUserDao {

  @Autowired private PlatformUserRepository platformUserRepository;

  /** 插入用户；ID 由 Service 层分配。 */
  public PlatformUserEntity insert(PlatformUserEntity entity) {
    return platformUserRepository.save(entity);
  }

  public Optional<PlatformUserEntity> findById(long userId) {
    return platformUserRepository.findById(userId);
  }

  /** 按 user_id 集合批量加载 nickname（plan_21/21.2），避免 Membership 列表逐行查询 User。 */
  public List<PlatformUserEntity> findByIdIn(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return List.of();
    }
    return platformUserRepository.findAllByIdIn(userIds);
  }
}
