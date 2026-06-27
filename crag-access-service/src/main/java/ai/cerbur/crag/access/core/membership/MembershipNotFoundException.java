package ai.cerbur.crag.access.core.membership;

/** 成员关系或被添加账号未找到，或调用方在指定 Tenant 无成员关系；统一不泄漏存在性。gRPC 映射为 MEMBERSHIP_NOT_FOUND。 */
public class MembershipNotFoundException extends RuntimeException {
  public MembershipNotFoundException() {
    super("membership not found");
  }
}
