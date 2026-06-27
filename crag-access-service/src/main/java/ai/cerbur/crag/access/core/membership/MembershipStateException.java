package ai.cerbur.crag.access.core.membership;

/** 非法成员状态迁移（如添加已是 ACTIVE 的成员）。gRPC 映射为 STATE_CONFLICT。 */
public class MembershipStateException extends RuntimeException {
  public MembershipStateException(String message) {
    super(message);
  }
}
