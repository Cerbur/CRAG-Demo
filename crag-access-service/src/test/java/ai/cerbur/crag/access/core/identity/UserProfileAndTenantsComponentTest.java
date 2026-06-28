package ai.cerbur.crag.access.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.cerbur.crag.access.core.membership.MembershipRole;
import ai.cerbur.crag.access.core.membership.UserTenantResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户安全投影与 Tenant 列表组件测试（plan_21/21.2）。
 *
 * <p>验证 {@link IdentityService#getProfile} 返回安全投影，{@link IdentityService#listUserTenants}
 * 分页稳定并附带用户在该 Tenant 的角色；不存在或无效用户统一不泄漏存在性。
 */
@SpringBootTest
@Transactional
class UserProfileAndTenantsComponentTest {

  @Autowired private IdentityService identityService;

  @Test
  @DisplayName("getProfile 返回 userId 与 nickname 安全投影")
  void getProfileReturnsSafeProjection() {
    RegisteredIdentity owner =
        identityService.register(
            new RegisterIdentityCommand(
                "AliceProfile", "aliceprofile21a", "correct-horse-battery-12".toCharArray()));

    UserProfileResult profile = identityService.getProfile(owner.userId());

    assertThat(profile.userId()).isEqualTo(owner.userId());
    assertThat(profile.nickname()).isEqualTo("AliceProfile");
  }

  @Test
  @DisplayName("getProfile 用户不存在抛 IllegalArgumentException")
  void getProfileUnknownUserThrows() {
    assertThrows(IllegalArgumentException.class, () -> identityService.getProfile(9_999_999L));
  }

  @Test
  @DisplayName("listUserTenants 返回该用户有效 Tenant 及角色，分页稳定")
  void listUserTenantsReturnsActiveWithRole() {
    RegisteredIdentity owner =
        identityService.register(
            new RegisterIdentityCommand(
                "OwnerTenants", "ownertenants21a", "correct-horse-battery-12".toCharArray()));

    List<UserTenantResult> tenants = identityService.listUserTenants(owner.userId(), 50, null);

    assertThat(tenants).hasSize(1);
    UserTenantResult tenant = tenants.get(0);
    assertThat(tenant.tenantId()).isEqualTo(owner.tenantId());
    assertThat(tenant.name()).contains("OwnerTenants");
    assertThat(tenant.role()).isEqualTo(MembershipRole.OWNER);
  }

  @Test
  @DisplayName("listUserTenants 无有效成员关系返回空列表")
  void listUserTenantsNoMembershipReturnsEmpty() {
    List<UserTenantResult> tenants = identityService.listUserTenants(9_999_998L, 50, null);
    assertThat(tenants).isEmpty();
  }
}
