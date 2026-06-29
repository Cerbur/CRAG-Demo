package ai.cerbur.crag.contracts.access.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey;
import ai.cerbur.crag.contracts.access.v1.IdentityServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.Membership;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Access contracts 兼容性架构测试。
 *
 * <p>plan_21/21.1 router4 扩展：在保持既有字段号稳定的前提下追加 Console/Open 管理用例所需的 RPC 与版本字段。 这里锁定新增 RPC 存在且
 * ApiKeyScope / AuthenticatedApiKey 的版本字段不与旧字段号冲突。
 */
class AccessContractsCompatibilityTest {

  private static Map<String, FieldDescriptor> fieldMap(Descriptor descriptor) {
    return descriptor.getFields().stream()
        .collect(Collectors.toMap(FieldDescriptor::getName, f -> f));
  }

  private static boolean hasRpc(ServiceDescriptor service, String bareMethodName) {
    return service.getMethods().stream()
        .map(MethodDescriptor::getBareMethodName)
        .anyMatch(bareMethodName::equals);
  }

  @Test
  @DisplayName("IdentityService 追加 GetUserProfile、ListUserTenants 与 Refresh Token Logout")
  void identityServiceAppendsManagementQueries() {
    ServiceDescriptor service = IdentityServiceGrpc.getServiceDescriptor();
    assertTrue(hasRpc(service, "GetUserProfile"), "缺少 GetUserProfile RPC");
    assertTrue(hasRpc(service, "ListUserTenants"), "缺少 ListUserTenants RPC");
  }

  @Test
  @DisplayName("MembershipService 保持既有 RPC 不变（兼容校验）")
  void membershipServiceBackwardCompatible() {
    ServiceDescriptor service = MembershipServiceGrpc.getServiceDescriptor();
    assertTrue(hasRpc(service, "AuthorizeTenantAction"));
    assertTrue(hasRpc(service, "AddMemberByUsername"));
    assertTrue(hasRpc(service, "ChangeMemberRole"));
    assertTrue(hasRpc(service, "RemoveMember"));
    assertTrue(hasRpc(service, "GetMembership"));
    assertTrue(hasRpc(service, "ListMemberships"));
  }

  @Test
  @DisplayName("Membership 追加 nickname 字段（字段号 9），且不与既有字段号冲突")
  void membershipAppendsNicknameField() {
    Map<String, FieldDescriptor> fields = fieldMap(Membership.getDescriptor());
    // 既有字段 1–8 保持稳定。
    assertEquals(1, fields.get("membership_id").getNumber());
    assertEquals(2, fields.get("tenant_id").getNumber());
    assertEquals(3, fields.get("user_id").getNumber());
    assertEquals(4, fields.get("role").getNumber());
    assertEquals(5, fields.get("status").getNumber());
    assertEquals(6, fields.get("created_at_epoch_millis").getNumber());
    assertEquals(7, fields.get("updated_at_epoch_millis").getNumber());
    assertEquals(8, fields.get("version").getNumber());
    // 新增 nickname 必须使用 >= 9 的字段号。
    FieldDescriptor nickname = fields.get("nickname");
    assertNotNull(nickname, "缺少 nickname");
    assertEquals(9, nickname.getNumber(), "nickname 字段号必须为 9");
  }

  @Test
  @DisplayName("ApiKeyService 追加 EnsureScope、GetScope、GetApiKey、ListApiKeys")
  void apiKeyServiceAppendsManagementRpcs() {
    ServiceDescriptor service = ApiKeyServiceGrpc.getServiceDescriptor();
    assertTrue(hasRpc(service, "EnsureScope"), "缺少 EnsureScope RPC");
    assertTrue(hasRpc(service, "GetScope"), "缺少 GetScope RPC");
    assertTrue(hasRpc(service, "GetApiKey"), "缺少 GetApiKey RPC");
    assertTrue(hasRpc(service, "ListApiKeys"), "缺少 ListApiKeys RPC");
  }

  @Test
  @DisplayName("ApiKeyScope 追加 keyVersion 与 scopeVersion，且不与既有字段号冲突")
  void apiKeyScopeVersionFieldsAppended() {
    Map<String, FieldDescriptor> fields = fieldMap(ApiKeyScope.getDescriptor());
    // 既有字段：1 knowledge_base_id, 2 tenant_id, 3 status, 4 version。
    assertEquals(1, fields.get("knowledge_base_id").getNumber());
    assertEquals(2, fields.get("tenant_id").getNumber());
    assertEquals(3, fields.get("status").getNumber());
    assertEquals(4, fields.get("version").getNumber());
    // 新增版本字段必须使用 >= 5 的字段号。
    FieldDescriptor keyVersion = fields.get("key_version");
    FieldDescriptor scopeVersion = fields.get("scope_version");
    assertNotNull(keyVersion, "缺少 key_version");
    assertNotNull(scopeVersion, "缺少 scope_version");
    assertEquals(5, keyVersion.getNumber(), "key_version 字段号必须为 5");
    assertEquals(6, scopeVersion.getNumber(), "scope_version 字段号必须为 6");
  }

  @Test
  @DisplayName("AuthenticatedApiKey 追加 keyVersion 与 scopeVersion")
  void authenticatedApiKeyVersionFieldsAppended() {
    Map<String, FieldDescriptor> fields = fieldMap(AuthenticatedApiKey.getDescriptor());
    // 既有：1 api_key_id, 2 tenant_id, 3 knowledge_base_id, 4 expires_at_epoch_millis。
    assertEquals(1, fields.get("api_key_id").getNumber());
    assertEquals(2, fields.get("tenant_id").getNumber());
    assertEquals(3, fields.get("knowledge_base_id").getNumber());
    assertEquals(4, fields.get("expires_at_epoch_millis").getNumber());
    assertNotNull(fields.get("key_version"), "缺少 key_version");
    assertNotNull(fields.get("scope_version"), "缺少 scope_version");
    assertEquals(5, fields.get("key_version").getNumber());
    assertEquals(6, fields.get("scope_version").getNumber());
  }
}
