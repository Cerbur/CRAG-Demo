package ai.cerbur.crag.access.core.membership;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TenantPermissionPolicy 纯单元测试：逐项验证 OWNER/MEMBER 权限矩阵。 */
class TenantPermissionPolicyTest {

  @Test
  @DisplayName("OWNER 可执行全部动作")
  void ownerAllowedAll() {
    for (TenantAction action : TenantAction.values()) {
      assertTrue(
          TenantPermissionPolicy.decide(MembershipRole.OWNER, action, true).allowed(),
          "owner should be allowed: " + action);
      assertTrue(
          TenantPermissionPolicy.decide(MembershipRole.OWNER, action, false).allowed(),
          "owner should be allowed regardless of ownership: " + action);
    }
  }

  @Test
  @DisplayName("MEMBER 权限矩阵逐项准确")
  void memberMatrix() {
    assertDeny(MembershipRole.MEMBER, TenantAction.MANAGE_MEMBERS);
    assertAllow(MembershipRole.MEMBER, TenantAction.CREATE_KNOWLEDGE_BASE);
    assertAllow(MembershipRole.MEMBER, TenantAction.VIEW_KNOWLEDGE_BASE);
    assertAllow(MembershipRole.MEMBER, TenantAction.UPLOAD_DOCUMENT);
    assertDeny(MembershipRole.MEMBER, TenantAction.DELETE_ANY_DOCUMENT);
    assertDeny(MembershipRole.MEMBER, TenantAction.DELETE_KNOWLEDGE_BASE);
    assertDeny(MembershipRole.MEMBER, TenantAction.MANAGE_API_KEY);
  }

  @Test
  @DisplayName("MEMBER 删除自有 Document 允许，删除他人 Document 拒绝")
  void memberDeleteOwnVsAny() {
    assertTrue(
        TenantPermissionPolicy.decide(MembershipRole.MEMBER, TenantAction.DELETE_OWN_DOCUMENT, true)
            .allowed());
    assertFalse(
        TenantPermissionPolicy.decide(
                MembershipRole.MEMBER, TenantAction.DELETE_OWN_DOCUMENT, false)
            .allowed());
  }

  @Test
  @DisplayName("拒绝决策携带稳定原因")
  void denyDecisionHasReason() {
    AuthorizationDecision decision =
        TenantPermissionPolicy.decide(MembershipRole.MEMBER, TenantAction.MANAGE_MEMBERS, false);
    assertFalse(decision.allowed());
    assertFalse(decision.reason().isBlank());
  }

  private static void assertAllow(MembershipRole role, TenantAction action) {
    assertTrue(
        TenantPermissionPolicy.decide(role, action, false).allowed(),
        "expected allow: " + role + " " + action);
  }

  private static void assertDeny(MembershipRole role, TenantAction action) {
    assertFalse(
        TenantPermissionPolicy.decide(role, action, false).allowed(),
        "expected deny: " + role + " " + action);
  }
}
