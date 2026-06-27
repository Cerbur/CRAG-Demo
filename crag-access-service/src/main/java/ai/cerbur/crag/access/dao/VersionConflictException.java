package ai.cerbur.crag.access.dao;

/**
 * 自定义更新受版本 CAS 保护，当 affected rows 为零（版本不匹配或状态前置不满足）时由 DAO 抛出。
 *
 * <p>调用方按业务语义处理抢占失败，不得盲目重试。
 */
public class VersionConflictException extends RuntimeException {

  public VersionConflictException(String message) {
    super(message);
  }
}
