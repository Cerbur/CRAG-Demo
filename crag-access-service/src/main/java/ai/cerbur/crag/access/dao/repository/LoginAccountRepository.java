package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * login_account Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>按规范化 Username 查询账号；(account_type, normalized_identifier) 由唯一索引兜底全局唯一。
 */
@Repository
public interface LoginAccountRepository extends JpaRepository<LoginAccountEntity, Long> {

  Optional<LoginAccountEntity> findByAccountTypeAndNormalizedIdentifier(
      String accountType, String normalizedIdentifier);

  Optional<LoginAccountEntity> findByUserId(long userId);
}
