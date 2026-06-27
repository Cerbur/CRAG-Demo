package ai.cerbur.crag.access.core.membership;

/** 调用方缺乏执行 Membership 管理动作所需角色（如非 OWNER 尝试管理成员）。gRPC 映射为 PERMISSION_DENIED。 */
public class MembershipAuthorizationException extends RuntimeException {
  public MembershipAuthorizationException() {
    super("permission denied");
  }
}
