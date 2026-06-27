package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.repository.LoginAccountRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** login_account 数据库访问边界，只依赖 {@link LoginAccountRepository}。 */
@Component
public class LoginAccountDao {

  @Autowired private LoginAccountRepository loginAccountRepository;

  /** 插入账号；ID 由 Service 层分配。(account_type, normalized_identifier) 由唯一索引兜底。 */
  public LoginAccountEntity insert(LoginAccountEntity entity) {
    return loginAccountRepository.save(entity);
  }

  /** 按规范化 Username 查询 USERNAME 账号；不存在返回空。 */
  public Optional<LoginAccountEntity> findByNormalizedUsername(String normalizedUsername) {
    return loginAccountRepository.findByAccountTypeAndNormalizedIdentifier(
        LoginAccountEntity.ACCOUNT_TYPE_USERNAME, normalizedUsername);
  }
}
