package ai.cerbur.crag.access.core.membership;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.identity.IdentityService;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.identity.RegisteredIdentity;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership 轻量组件测试（H2）。
 *
 * <p>验证成员生命周期、角色调整、重新加入复用、最后 OWNER 保护与跨租户/不存在账号的统一错误。真实 PostgreSQL 并发与最后 OWNER 锁语义由 Docker HTTP
 * 回归证明。
 */
@SpringBootTest
@Transactional
class MembershipComponentTest {

  private static final String PASSWORD = "correct-horse-battery-12";

  @Autowired private MembershipService membershipService;
  @Autowired private IdentityService identityService;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("OWNER 权限允许管理成员，MEMBER 拒绝管理成员")
  void authorizeMatrix() {
    RegisteredIdentity owner = register("ownerA", "ownera");
    RegisteredIdentity member = addMember(owner, "memberA", "membera");

    assertTrue(
        membershipService
            .authorize(
                new AuthorizationRequest(
                    owner.userId(), owner.tenantId(), TenantAction.MANAGE_MEMBERS, null))
            .allowed());
    assertFalse(
        membershipService
            .authorize(
                new AuthorizationRequest(
                    member.userId(), owner.tenantId(), TenantAction.MANAGE_MEMBERS, null))
            .allowed());
  }

  @Test
  @DisplayName("按 Username 添加已注册用户为 MEMBER")
  void addByUsernameCreatesMember() {
    RegisteredIdentity owner = register("ownerB", "ownerb");
    RegisteredIdentity member = register("memberB", "memberb");
    MembershipResult added =
        membershipService.addByUsername(owner.userId(), owner.tenantId(), "memberb");
    assertEquals(MembershipRole.MEMBER, added.role());
    assertEquals(member.userId(), added.userId());
  }

  @Test
  @DisplayName("添加不存在 Username 抛 MembershipNotFoundException，不泄漏账号状态")
  void addByUsernameMissing() {
    RegisteredIdentity owner = register("ownerC", "ownerc");
    assertThrows(
        MembershipNotFoundException.class,
        () -> membershipService.addByUsername(owner.userId(), owner.tenantId(), "ghost"));
  }

  @Test
  @DisplayName("MEMBER 不能添加成员，抛 MembershipAuthorizationException")
  void memberCannotManage() {
    RegisteredIdentity owner = register("ownerD", "ownerd");
    RegisteredIdentity member = addMember(owner, "memberD", "memberd");
    register("extraD", "extrad");
    assertThrows(
        MembershipAuthorizationException.class,
        () -> membershipService.addByUsername(member.userId(), owner.tenantId(), "extrad"));
  }

  @Test
  @DisplayName("移除成员后重新加入复用原行并设为 MEMBER")
  void removeAndRejoinReusesRow() {
    RegisteredIdentity owner = register("ownerE", "ownere");
    RegisteredIdentity member = addMember(owner, "memberE", "membere");
    MembershipResult first =
        membershipService.get(owner.userId(), owner.tenantId(), member.userId());

    membershipService.remove(owner.userId(), owner.tenantId(), member.userId());
    entityManager.flush();
    entityManager.clear();

    MembershipResult rejoined =
        membershipService.addByUsername(owner.userId(), owner.tenantId(), "membere");
    assertEquals(first.membershipId(), rejoined.membershipId());
    assertEquals(MembershipRole.MEMBER, rejoined.role());
  }

  @Test
  @DisplayName("添加已是 ACTIVE 的成员抛 MembershipStateException")
  void addActiveMemberAgainFails() {
    RegisteredIdentity owner = register("ownerF", "ownerf");
    addMember(owner, "memberF", "memberf");
    assertThrows(
        MembershipStateException.class,
        () -> membershipService.addByUsername(owner.userId(), owner.tenantId(), "memberf"));
  }

  @Test
  @DisplayName("移除最后一名 OWNER 抛 LastOwnerException")
  void removeLastOwnerFails() {
    RegisteredIdentity owner = register("ownerG", "ownerg");
    assertThrows(
        LastOwnerException.class,
        () -> membershipService.remove(owner.userId(), owner.tenantId(), owner.userId()));
  }

  @Test
  @DisplayName("降级最后一名 OWNER 抛 LastOwnerException；有多名 OWNER 时可移除")
  void lastOwnerProtectionOnRoleChange() {
    RegisteredIdentity owner = register("ownerH", "ownerh");
    RegisteredIdentity member = addMember(owner, "memberH", "memberh");
    assertThrows(
        LastOwnerException.class,
        () ->
            membershipService.changeRole(
                owner.userId(), owner.tenantId(), owner.userId(), MembershipRole.MEMBER));

    // 升级 member 为 OWNER，此时有两名 OWNER，可移除原 OWNER
    membershipService.changeRole(
        owner.userId(), owner.tenantId(), member.userId(), MembershipRole.OWNER);
    assertDoesNotThrow(
        () -> membershipService.remove(owner.userId(), owner.tenantId(), owner.userId()));
  }

  @Test
  @DisplayName("跨租户查询不泄漏 Membership")
  void crossTenantDoesNotLeak() {
    RegisteredIdentity ownerA = register("ownerI", "owneri");
    RegisteredIdentity ownerB = register("ownerJ", "ownerj");
    // ownerA 不是 ownerB 租户的成员
    assertThrows(
        MembershipNotFoundException.class,
        () -> membershipService.get(ownerA.userId(), ownerB.tenantId(), ownerB.userId()));
  }

  @Test
  @DisplayName("list 返回 Tenant 有效成员，并批量补齐 nickname")
  void listReturnsActiveMembersWithNickname() {
    RegisteredIdentity owner = register("ownerK", "ownerk");
    addMember(owner, "memberK", "memberk");
    String ownerNickname = identityService.getProfile(owner.userId()).nickname();
    List<MembershipResult> members =
        membershipService.list(owner.userId(), owner.tenantId(), 50, null);
    assertTrue(members.size() >= 2);
    // nickname 由批量用户查询补齐（非 N+1），所有返回项都有非空展示名。
    assertTrue(members.stream().allMatch(m -> m.nickname() != null && !m.nickname().isBlank()));
    assertTrue(
        members.stream()
            .anyMatch(m -> m.userId() == owner.userId() && ownerNickname.equals(m.nickname())));
    assertTrue(members.stream().anyMatch(m -> "memberK".equals(m.nickname())));
  }

  private RegisteredIdentity register(String nickname, String username) {
    RegisteredIdentity registered =
        identityService.register(
            new RegisterIdentityCommand(nickname, username, PASSWORD.toCharArray()));
    entityManager.flush();
    return registered;
  }

  private RegisteredIdentity addMember(RegisteredIdentity owner, String nickname, String username) {
    RegisteredIdentity member =
        identityService.register(
            new RegisterIdentityCommand(nickname, username, PASSWORD.toCharArray()));
    entityManager.flush();
    membershipService.addByUsername(owner.userId(), owner.tenantId(), username);
    entityManager.flush();
    entityManager.clear();
    return member;
  }
}
