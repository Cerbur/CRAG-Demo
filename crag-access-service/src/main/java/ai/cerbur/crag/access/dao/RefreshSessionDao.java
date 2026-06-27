package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.RefreshSessionEntity;
import ai.cerbur.crag.access.dao.repository.RefreshSessionRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * refresh_session 数据库访问边界，只依赖 {@link RefreshSessionRepository}。
 *
 * <p>刷新以悲观锁按 HMAC 定位 Token 并通过版本 CAS 单次轮换；affected rows 为零抛 {@link VersionConflictException}，
 * 由调用方按复用检测或并发处理。Family 撤销为受控批量更新。
 */
@Component
public class RefreshSessionDao {

  @Autowired private RefreshSessionRepository refreshSessionRepository;

  /** 插入会话行；session_id 与 family_id 由 Service 层分配。 */
  public RefreshSessionEntity insert(RefreshSessionEntity entity) {
    return refreshSessionRepository.save(entity);
  }

  /** 悲观锁按 token_hmac 定位会话。 */
  public Optional<RefreshSessionEntity> findByTokenHmacForUpdate(String tokenHmac) {
    return refreshSessionRepository.findByTokenHmacForUpdate(tokenHmac);
  }

  /** 悲观锁读取 Family 内指定状态会话。 */
  public List<RefreshSessionEntity> findForUpdateByFamilyAndStatuses(
      long familyId, Collection<String> statuses) {
    return refreshSessionRepository.findByFamilyIdAndStatusInForUpdate(familyId, statuses);
  }

  /**
   * 轮换版本 CAS：ACTIVE→ROTATED，记录替代会话 ID。
   *
   * @return 更新后重新读取的会话
   * @throws VersionConflictException 版本/状态不匹配（affected rows 为零）
   */
  public RefreshSessionEntity rotate(long sessionId, long expectedVersion, long replacedBy) {
    int affected =
        refreshSessionRepository.rotate(
            sessionId,
            expectedVersion,
            replacedBy,
            RefreshSessionEntity.STATUS_ACTIVE,
            RefreshSessionEntity.STATUS_ROTATED,
            LocalDateTime.now());
    if (affected == 0) {
      throw new VersionConflictException(
          "refresh session rotate CAS failed: sessionId="
              + sessionId
              + " version="
              + expectedVersion);
    }
    return refreshSessionRepository
        .findById(sessionId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "refresh session vanished after CAS: sessionId=" + sessionId));
  }

  /** 撤销整 Family 内仍在使用的会话（ACTIVE/ROTATED）；返回受影响行数。 */
  public int revokeFamily(long familyId) {
    return refreshSessionRepository.revokeFamily(
        familyId,
        List.of(RefreshSessionEntity.STATUS_ACTIVE, RefreshSessionEntity.STATUS_ROTATED),
        RefreshSessionEntity.STATUS_REVOKED,
        LocalDateTime.now());
  }
}
