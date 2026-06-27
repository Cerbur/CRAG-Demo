package ai.cerbur.crag.access.producer;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.apikey.ApiKeyService;
import ai.cerbur.crag.access.core.apikey.CreatedApiKey;
import ai.cerbur.crag.access.core.identity.IdentityService;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.identity.RegisteredIdentity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * API Key 失效 Outbox Producer 轻量组件测试（H2）。
 *
 * <p>验证 Disable/Enable/Rotate/Revoke/Scope Block 各在同事务写一条 PENDING {@code API_KEY_INVALIDATED}
 * 事件，携带正确的资源定位、 action 与版本；事务回滚时事件一并回滚。
 */
@SpringBootTest
@Transactional
class AccessEventProducerComponentTest {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private IdentityService identityService;
  @Autowired private NamedParameterJdbcTemplate jdbc;

  @Test
  @DisplayName("Disable 写一条 PENDING 事件，resourceType=API_KEY")
  void disableWritesEvent() {
    RegisteredIdentity owner = register("pownerA", "pownera");
    long kb = 9101L;
    CreatedApiKey key = createKey(owner, kb, "k");
    apiKeyService.disable(owner.userId(), owner.tenantId(), key.apiKeyId());
    Map<String, Object> row = latestEvent(key.apiKeyId());
    assertEquals(AccessEventTypes.API_KEY_INVALIDATED, row.get("event_type"));
    assertEquals(AccessEventTypes.RESOURCE_API_KEY, row.get("resource_type"));
    assertEquals("DISABLED", readPayloadAction(row));
    assertEquals("PENDING", row.get("status"));
  }

  @Test
  @DisplayName("Enable 与 Revoke 各写一条事件")
  void enableAndRevokeWriteEvents() {
    RegisteredIdentity owner = register("pownerB", "pownerb");
    long kb = 9102L;
    CreatedApiKey key = createKey(owner, kb, "k");
    apiKeyService.disable(owner.userId(), owner.tenantId(), key.apiKeyId());
    apiKeyService.enable(owner.userId(), owner.tenantId(), key.apiKeyId());
    assertEquals("ENABLED", readPayloadAction(latestEvent(key.apiKeyId())));
    apiKeyService.revoke(owner.userId(), owner.tenantId(), key.apiKeyId());
    assertEquals("REVOKED", readPayloadAction(latestEvent(key.apiKeyId())));
  }

  @Test
  @DisplayName("Rotate 为旧 Key 写一条 ROTATED 事件")
  void rotateWritesEvent() {
    RegisteredIdentity owner = register("pownerC", "pownerc");
    long kb = 9103L;
    CreatedApiKey key = createKey(owner, kb, "k");
    apiKeyService.rotate(owner.userId(), owner.tenantId(), key.apiKeyId(), Duration.ofDays(30));
    Map<String, Object> row = latestEvent(key.apiKeyId());
    assertEquals("ROTATED", readPayloadAction(row));
    assertEquals(AccessEventTypes.RESOURCE_API_KEY, row.get("resource_type"));
  }

  @Test
  @DisplayName("Scope Block 写一条 resourceType=API_KEY_SCOPE 事件")
  void scopeBlockWritesEvent() {
    RegisteredIdentity owner = register("pownerD", "pownerd");
    long kb = 9104L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    apiKeyService.blockScope(owner.userId(), owner.tenantId(), kb);
    Map<String, Object> row =
        jdbc
            .queryForList(
                "SELECT * FROM outbox_event WHERE event_type = :t AND resource_id = :rid ORDER BY event_id DESC LIMIT 1",
                new MapSqlParameterSource()
                    .addValue("t", AccessEventTypes.API_KEY_INVALIDATED)
                    .addValue("rid", kb))
            .stream()
            .findFirst()
            .orElseThrow();
    assertEquals(AccessEventTypes.RESOURCE_API_KEY_SCOPE, row.get("resource_type"));
    assertEquals("SCOPE_BLOCKED", readPayloadAction(row));
    assertEquals(kb, ((Number) row.get("resource_id")).longValue());
  }

  @Test
  @DisplayName("事件 payload version 固定为 1")
  void payloadVersionIsOne() {
    RegisteredIdentity owner = register("pownerE", "pownere");
    long kb = 9105L;
    CreatedApiKey key = createKey(owner, kb, "k");
    apiKeyService.revoke(owner.userId(), owner.tenantId(), key.apiKeyId());
    Map<String, Object> row = latestEvent(key.apiKeyId());
    assertEquals(1, ((Number) row.get("payload_version")).intValue());
    assertEquals(1, AccessEventTypes.PAYLOAD_VERSION);
  }

  private CreatedApiKey createKey(RegisteredIdentity owner, long kb, String name) {
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    return apiKeyService.create(owner.userId(), owner.tenantId(), kb, name, Duration.ofDays(30));
  }

  private RegisteredIdentity register(String nickname, String username) {
    return identityService.register(
        new RegisterIdentityCommand(nickname, username, "correct-horse-battery-12".toCharArray()));
  }

  private Map<String, Object> latestEvent(long resourceId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT * FROM outbox_event WHERE event_type = :t AND resource_id = :rid ORDER BY event_id DESC LIMIT 1",
            new MapSqlParameterSource()
                .addValue("t", AccessEventTypes.API_KEY_INVALIDATED)
                .addValue("rid", resourceId));
    return rows.stream().findFirst().orElseThrow(() -> new AssertionError("no outbox event"));
  }

  @SuppressWarnings("unchecked")
  private static String readPayloadAction(Map<String, Object> row) {
    String payload = (String) row.get("payload_json");
    Map<String, Object> parsed;
    try {
      parsed = new tools.jackson.databind.ObjectMapper().readValue(payload, Map.class);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return (String) parsed.get("action");
  }
}
