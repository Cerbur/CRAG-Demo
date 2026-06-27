package ai.cerbur.crag.access.core.apikey;

/** 操作的 Scope 已被终态阻塞。gRPC 映射为 SCOPE_BLOCKED。 */
public class ScopeBlockedException extends RuntimeException {
  public ScopeBlockedException() {
    super("scope is blocked");
  }
}
