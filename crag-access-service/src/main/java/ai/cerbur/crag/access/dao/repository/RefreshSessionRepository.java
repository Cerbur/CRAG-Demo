package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.RefreshSessionEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * refresh_session Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>刷新以悲观写锁按 HMAC 定位 Token，再通过版本 CAS 单次轮换；ROTATED Token 复用触发整 Family 撤销。
 */
@Repository
public interface RefreshSessionRepository extends JpaRepository<RefreshSessionEntity, Long> {

  /** 按 token_hmac 定位会话（非锁）。 */
  @Query("SELECT s FROM RefreshSessionEntity s WHERE s.tokenHmac = :tokenHmac")
  Optional<RefreshSessionEntity> findByTokenHmac(@Param("tokenHmac") String tokenHmac);

  /** 悲观锁按 token_hmac 定位会话。 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM RefreshSessionEntity s WHERE s.tokenHmac = :tokenHmac")
  Optional<RefreshSessionEntity> findByTokenHmacForUpdate(@Param("tokenHmac") String tokenHmac);

  /** 悲观锁读取 Family 内指定状态会话。 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT s FROM RefreshSessionEntity s WHERE s.familyId = :familyId AND s.status IN :statuses")
  List<RefreshSessionEntity> findByFamilyIdAndStatusInForUpdate(
      @Param("familyId") long familyId, @Param("statuses") Collection<String> statuses);

  /** 轮换版本 CAS：ACTIVE→ROTATED，记录替代会话；返回 affected rows。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE RefreshSessionEntity s SET s.status = :rotated, s.rotatedAt = :now, "
          + "s.replacedBy = :replacedBy, s.version = s.version + 1, s.updatedAt = :now "
          + "WHERE s.sessionId = :sessionId AND s.version = :expectedVersion AND s.status = :active")
  int rotate(
      @Param("sessionId") long sessionId,
      @Param("expectedVersion") long expectedVersion,
      @Param("replacedBy") long replacedBy,
      @Param("active") String activeStatus,
      @Param("rotated") String rotatedStatus,
      @Param("now") LocalDateTime now);

  /** 撤销整 Family 内仍在使用的会话（ACTIVE/ROTATED）。批量受控更新，非逐条 CAS。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE RefreshSessionEntity s SET s.status = :revoked, s.revokedAt = :now, "
          + "s.version = s.version + 1, s.updatedAt = :now "
          + "WHERE s.familyId = :familyId AND s.status IN :activeStatuses")
  int revokeFamily(
      @Param("familyId") long familyId,
      @Param("activeStatuses") Collection<String> activeStatuses,
      @Param("revoked") String revokedStatus,
      @Param("now") LocalDateTime now);
}
