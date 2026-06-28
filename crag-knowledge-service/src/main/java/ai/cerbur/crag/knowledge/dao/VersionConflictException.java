package ai.cerbur.crag.knowledge.dao;

/**
 * Knowledge 持久化 CAS 抢占失败语义异常（plan_21/21.3）。
 *
 * <p>当自定义 {@code @Modifying} 更新返回 0 行（version、operationVersion、tenant、knowledgeBase 或 docId
 * 任一不匹配）时，DAO 抛出本异常，调用方按版本/归属冲突的业务语义处理。禁止用 {@code DuplicateKeyException} 冒充版本冲突（见 {@code
 * constraints/persistence-style.md}）。
 */
public class VersionConflictException extends RuntimeException {

  public VersionConflictException(String message) {
    super(message);
  }
}
