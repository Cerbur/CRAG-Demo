package ai.cerbur.crag.access.core.apikey;

/**
 * Scope 状态冲突，例如同一 KnowledgeBase 在不同 Tenant 之间归属不一致。gRPC 映射为 FAILED_PRECONDITION。
 *
 * <p>plan_21/21.2：EnsureScope 检测到 KnowledgeBase 已属于其他 Tenant 时抛出，保护租户隔离边界，不悄悄改写归属。
 */
public class ScopeStateException extends RuntimeException {
  public ScopeStateException(String message) {
    super(message);
  }
}
